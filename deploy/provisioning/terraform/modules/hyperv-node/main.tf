locals {
  mac_lower = lower(var.mac)
  # Linux form of the Hyper-V MAC, for the netplan match in network-config.
  mac_colon = join(":", [for i in range(0, 12, 2) : substr(local.mac_lower, i, 2)])

  user_data = templatefile("${path.module}/templates/user-data.yaml.tftpl", {
    hostname            = var.hostname
    ssh_user            = var.ssh_user
    ssh_authorized_keys = var.ssh_authorized_keys
  })
  network_config = templatefile("${path.module}/templates/network-config.yaml.tftpl", {
    mac_colon     = local.mac_colon
    ipv4          = var.ipv4
    prefix_length = var.network.prefix_length
    gateway       = var.network.gateway
    dns_servers   = var.network.dns_servers
    search        = var.network.search
  })
  # A new instance-id makes cloud-init treat the VM as new and re-apply the
  # seed, which only matters when the VM is rebuilt from a fresh disk.
  instance_id = "${var.name}-${substr(sha256("${local.user_data}${local.network_config}"), 0, 12)}"
  meta_data = yamlencode({
    "instance-id"    = local.instance_id
    "local-hostname" = var.hostname
  })

  os_disk_path  = "${var.vm_root}\\${var.name}-os.vhdx"
  seed_iso_path = "${var.vm_root}\\${var.name}-cidata.iso"
}

# cloud-init NoCloud seed: files meta-data, user-data, network-config on a
# volume labelled CIDATA, attached as a DVD. Built locally, uploaded and
# turned into an ISO on the host by the provider.
data "archive_file" "seed" {
  type        = "zip"
  output_path = "${path.root}/.seed/${var.name}.zip"

  source {
    filename = "meta-data"
    content  = local.meta_data
  }
  source {
    filename = "user-data"
    content  = local.user_data
  }
  source {
    filename = "network-config"
    content  = local.network_config
  }
}

resource "hyperv_iso_image" "seed" {
  volume_name               = "CIDATA"
  source_zip_file_path      = data.archive_file.seed.output_path
  source_zip_file_path_hash = data.archive_file.seed.output_sha
  destination_iso_file_path = local.seed_iso_path
  iso_media_type            = "cdrom"
  iso_file_system_type      = "iso9660|joliet"
}

# Differencing disk over the golden image: fast to create, and replacing a
# node means discarding only this child disk. The golden image must never be
# modified in place (New-BaseImage.ps1 writes a new, dated file instead).
resource "hyperv_vhd" "os" {
  path        = local.os_disk_path
  parent_path = var.base_image_path
  vhd_type    = "Differencing"
}

resource "hyperv_machine_instance" "this" {
  name                   = var.name
  generation             = 2
  processor_count        = var.cpus
  static_memory          = true
  memory_startup_bytes   = var.memory_mb * 1024 * 1024
  state                  = "Running"
  automatic_start_action = "Start"
  automatic_stop_action  = "ShutDown"
  # No checkpoints: restoring a checkpoint of an etcd member rewinds its log
  # and can corrupt the cluster. Recover by rebuilding the node instead.
  checkpoint_type = "Disabled"
  notes           = "iotee ${var.role} node ${var.hostname}; managed by Terraform (deploy/provisioning)"

  vm_firmware {
    enable_secure_boot   = "On"
    secure_boot_template = "MicrosoftUEFICertificateAuthority"
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
    static_mac_address  = upper(var.mac)
  }

  hard_disk_drives {
    controller_type     = "Scsi"
    controller_number   = "0"
    controller_location = "0"
    path                = hyperv_vhd.os.path
  }

  dvd_drives {
    controller_number   = "0"
    controller_location = "1"
    path                = hyperv_iso_image.seed.resolve_destination_iso_file_path
  }
}
