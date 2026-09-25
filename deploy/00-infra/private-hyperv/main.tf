terraform {
  # OpenTofu >= 1.6 or Terraform >= 1.6. CI validates with OpenTofu.
  required_version = ">= 1.6.0"

  required_providers {
    # Community Hyper-V provider, talking to the host over WinRM/HTTPS.
    hyperv = {
      source  = "taliesins/hyperv"
      version = "~> 1.2.1"
    }
    # Packs each VM's cloud-init seed files for hyperv_iso_image.
    archive = {
      source  = "hashicorp/archive"
      version = ">= 2.4.0, < 3.0.0"
    }
  }

  # State stays on the operator machine (*.tfstate* is git-ignored at the
  # repository root). Move to a locking remote backend before a second
  # operator runs apply.
  backend "local" {
    path = "terraform.tfstate"
  }
}

provider "hyperv" {
  host     = var.hyperv_host.address
  port     = var.hyperv_host.port
  https    = true
  insecure = var.hyperv_host.insecure
  use_ntlm = true
  user     = var.hyperv_user
  password = var.hyperv_password
  timeout  = var.hyperv_host.timeout
}

locals {
  prefix_length  = tonumber(split("/", var.network.cidr)[1])
  ssh_public_key = trimspace(file(pathexpand(var.ssh_public_key_path)))

  nodes = {
    for name, n in var.nodes : name => merge(n, {
      ipv4      = cidrhost(var.network.cidr, n.ip_host)
      mac_upper = upper(n.mac)
      # Linux spelling of the same MAC, for the netplan match below.
      mac_colon = join(":", [for i in range(0, 12, 2) : substr(lower(n.mac), i, 2)])
    })
  }

  # cloud-init NoCloud seed, deliberately minimal (Layer 0 scope): hostname,
  # static network, one login with one SSH public key. No packages, no
  # commands, no Kubernetes; all software setup belongs to Layer 1.
  seed = {
    for name, n in local.nodes : name => {
      user_data = join("\n", [
        "#cloud-config",
        yamlencode({
          hostname          = name
          preserve_hostname = false
          manage_etc_hosts  = true
          ssh_pwauth        = false
          disable_root      = true
          users = [{
            name                = var.admin_user
            groups              = ["sudo"]
            shell               = "/bin/bash"
            sudo                = "ALL=(ALL) NOPASSWD:ALL"
            lock_passwd         = true
            ssh_authorized_keys = [local.ssh_public_key]
          }]
        }),
      ])
      network_config = yamlencode({
        network = {
          version = 2
          ethernets = {
            eth0 = {
              match       = { macaddress = n.mac_colon }
              "set-name"  = "eth0"
              dhcp4       = false
              addresses   = ["${n.ipv4}/${local.prefix_length}"]
              routes      = [{ to = "default", via = var.network.gateway }]
              nameservers = { addresses = var.network.dns_servers }
            }
          }
        }
      })
    }
  }
}

# --- cloud-init seed ISO (volume label CIDATA), one per VM -------------------

data "archive_file" "seed" {
  for_each    = local.seed
  type        = "zip"
  output_path = "${path.module}/.seed/${each.key}.zip"

  source {
    filename = "meta-data"
    content = yamlencode({
      # A content hash in instance-id makes cloud-init re-apply the seed only
      # when it actually changes (i.e. when the VM is rebuilt from a new disk).
      "instance-id"    = "${each.key}-${substr(sha256("${each.value.user_data}${each.value.network_config}"), 0, 12)}"
      "local-hostname" = each.key
    })
  }
  source {
    filename = "user-data"
    content  = each.value.user_data
  }
  source {
    filename = "network-config"
    content  = each.value.network_config
  }
}

resource "hyperv_iso_image" "seed" {
  for_each                  = local.nodes
  volume_name               = "CIDATA"
  source_zip_file_path      = data.archive_file.seed[each.key].output_path
  source_zip_file_path_hash = data.archive_file.seed[each.key].output_sha
  destination_iso_file_path = "${var.vm_root}\\${each.key}-cidata.iso"
  iso_media_type            = "cdrom"
  iso_file_system_type      = "iso9660|joliet"
}

# --- disks --------------------------------------------------------------------

# OS disk: differencing child of the read-only golden template. Its size is the
# template's (set by the prep script); rebuilding a VM discards only this file.
resource "hyperv_vhd" "os" {
  for_each    = local.nodes
  path        = "${var.vm_root}\\${each.key}-os.vhdx"
  parent_path = var.template_vhdx_path
  vhd_type    = "Differencing"
}

# Data disk: empty, dynamically expanding, sized per node. Left unformatted;
# Layer 1 partitions and mounts it (e.g. for /var/lib/rancher).
resource "hyperv_vhd" "data" {
  for_each = local.nodes
  path     = "${var.vm_root}\\${each.key}-data.vhdx"
  vhd_type = "Dynamic"
  size     = each.value.data_disk_gb * 1024 * 1024 * 1024
}

# --- VMs ------------------------------------------------------------------------

resource "hyperv_machine_instance" "vm" {
  for_each = local.nodes

  name                   = each.key
  generation             = 2
  processor_count        = each.value.cpus
  static_memory          = true
  memory_startup_bytes   = each.value.memory_mb * 1024 * 1024
  state                  = "Running"
  automatic_start_action = "Start"
  automatic_stop_action  = "ShutDown"
  # No checkpoints: restoring a checkpoint of an etcd member rewinds its log
  # and can corrupt the cluster. Rebuild a broken VM instead.
  checkpoint_type = "Disabled"
  notes           = "iotee ${each.value.role} ${each.value.ipv4}; managed by Terraform (deploy/00-infra/private-hyperv)"

  lifecycle {
    precondition {
      condition     = each.value.ipv4 != var.network.gateway
      error_message = "${each.key} resolves to ${each.value.ipv4}, which is the gateway (the host's NAT address)."
    }
  }

  vm_firmware {
    enable_secure_boot   = "On"
    secure_boot_template = var.secure_boot_template
    boot_order {
      boot_type           = "HardDiskDrive"
      controller_number   = "0"
      controller_location = "0"
    }
  }

  integration_services = {
    "Guest Service Interface" = false
    "Heartbeat"               = true
    "Key-Value Pair Exchange" = true
    "Shutdown"                = true
    "Time Synchronization"    = true
    "VSS"                     = true
  }

  network_adaptors {
    name                = "eth0"
    switch_name         = var.switch_name
    dynamic_mac_address = false
    static_mac_address  = each.value.mac_upper
  }

  hard_disk_drives {
    controller_type     = "Scsi"
    controller_number   = "0"
    controller_location = "0"
    path                = hyperv_vhd.os[each.key].path
  }

  hard_disk_drives {
    controller_type     = "Scsi"
    controller_number   = "0"
    controller_location = "1"
    path                = hyperv_vhd.data[each.key].path
  }

  dvd_drives {
    controller_number   = "0"
    controller_location = "2"
    path                = hyperv_iso_image.seed[each.key].resolve_destination_iso_file_path
  }
}
