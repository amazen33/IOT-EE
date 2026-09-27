terraform {
  # OpenTofu >= 1.6 or Terraform >= 1.6. CI validates with OpenTofu.
  required_version = ">= 1.6.0"

  required_providers {
    # Run OpenTofu directly on the Hyper-V host (backend = "local", below).
    # Pinned to an exact patch version, not a `~>`
    # range: the provider is pre-1.0 (breaking changes are possible between
    # any two 0.x releases) and its own docs (0.4.0) still say "Integration
    # services, automatic start/stop actions, and checkpoints are not
    # currently exposed" -- review this pin, and the
    # null_resource.vm_unmanaged_settings block below, on every upgrade.
    #
    # Explicit registry.terraform.io hostname, not the bare "windsorcli/hyperv"
    # shorthand: this provider is published only to the Terraform Registry,
    # not to OpenTofu's own registry.opentofu.org (confirmed 2026-09-26 --
    # `tofu init` against the bare source failed with "provider registry
    # registry.opentofu.org does not have a provider named
    # registry.opentofu.org/windsorcli/hyperv"). OpenTofu speaks the same
    # provider registry protocol as Terraform, so an explicit hostname works;
    # this is not a local/private mirror.
    #
    # Pinned to 0.4.0, not 0.3.2: a v0.3.2 GitHub release/tag exists, but
    # registry.terraform.io's own version list (confirmed 2026-09-26 via its
    # /v1/providers/windsorcli/hyperv/versions endpoint) does not include it
    # -- `tofu init` against "= 0.3.2" failed with "no available releases
    # match the given constraints 0.3.2". 0.4.0 is the newest version that
    # registry actually serves.
    hyperv = {
      source  = "registry.terraform.io/windsorcli/hyperv"
      version = "= 0.4.0"
    }
  }

  # State stays on the operator machine (*.tfstate* is git-ignored in this
  # directory). Move to a locking remote backend before a second
  # operator runs apply.
  backend "local" {
    path = "terraform.tfstate"
  }
}

# Local execution: the provider talks to Hyper-V's own WMI/CIM surface in
# the same process, as the account running `tofu`, which must itself be
# elevated and a member of Hyper-V Administrators. No host, port, or
# credentials -- there is no WinRM listener in this path at all. The
# provider also supports "ssh" and "winrm" backends for a Hyper-V host
# other than the one running `tofu`; this module is explicitly
# single-host and does not use them. Re-introduce the WinRM/SSH form, with its own credential
# variables, in a separate reviewed change if a remote host is ever needed.
provider "hyperv" {
  backend = "local"
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

  # data.hyperv_iso_volume synthesizes the ISO itself from a filename ->
  # content map (windsorcli/hyperv), so there is no intermediate zip step
  # here any more (taliesins/hyperv's hyperv_iso_image took a zip; this
  # provider's hyperv_image_file takes bytes directly). meta-data's
  # instance-id still changes only when user-data or network-config
  # actually change, so cloud-init re-applies the seed only when the VM is
  # rebuilt from a new disk, same as before.
  seed_files = {
    for name, s in local.seed : name => {
      "meta-data" = yamlencode({
        "instance-id"    = "${name}-${substr(sha256("${s.user_data}${s.network_config}"), 0, 12)}"
        "local-hostname" = name
      })
      "user-data"      = s.user_data
      "network-config" = s.network_config
    }
  }
}

# --- cloud-init seed ISO (volume label CIDATA), one per VM -------------------

data "hyperv_iso_volume" "seed" {
  for_each     = local.seed_files
  volume_label = "CIDATA"
  files        = each.value
}

resource "hyperv_image_file" "seed" {
  for_each         = local.nodes
  destination_path = "${var.vm_root}\\${each.key}-cidata.iso"
  content_base64   = data.hyperv_iso_volume.seed[each.key].content_base64
}

# --- disks --------------------------------------------------------------------

# OS disk: differencing child of the read-only golden template. Its size is the
# template's (set by the prep script); rebuilding a VM discards only this file.
resource "hyperv_vhd" "os" {
  for_each    = local.nodes
  path        = "${var.vm_root}\\${each.key}-os.vhdx"
  parent_path = var.template_vhdx_path
  vhd_type    = "differencing"
}

# Data disk: empty, dynamically expanding, sized per node. Left unformatted;
# Layer 1 partitions and mounts it (e.g. for /var/lib/rancher).
resource "hyperv_vhd" "data" {
  for_each   = local.nodes
  path       = "${var.vm_root}\\${each.key}-data.vhdx"
  vhd_type   = "dynamic"
  size_bytes = each.value.data_disk_gb * 1024 * 1024 * 1024
}

# --- VMs ------------------------------------------------------------------------

resource "hyperv_vm" "vm" {
  for_each = local.nodes

  name       = each.key
  generation = 2
  cpu        = { count = each.value.cpus }
  memory     = { startup_bytes = each.value.memory_mb * 1024 * 1024 }

  # Created powered OFF, not Running: this VM's own creation happens before
  # null_resource.vm_unmanaged_settings (below) gets to disable checkpoints
  # and Automatic Checkpoints on it. A VM created already Running would boot
  # with Hyper-V's defaults (checkpoints enabled, Automatic Checkpoints on)
  # still in effect and take an automatic checkpoint at that first boot --
  # confirmed for real on 2026-09-26, not a hypothetical: two lab VMs created
  # this way both got their hard_disk_drive.path silently rewritten to a
  # GUID-suffixed .avhdx (active differencing disk from that checkpoint),
  # which the provider then reported as "Provider produced inconsistent
  # result after apply". The safeguard now starts the VM itself, after
  # disabling checkpoints -- see the resource below.
  state = { desired = "Off" }

  secure_boot          = true
  secure_boot_template = var.secure_boot_template

  notes = "${each.value.role} ${each.value.ipv4}; managed by OpenTofu Layer 0"

  lifecycle {
    precondition {
      condition     = each.value.ipv4 != var.network.gateway
      error_message = "${each.key} resolves to ${each.value.ipv4}, which is the gateway (the host's NAT address)."
    }
  }

  network_adapter = [
    {
      name        = "eth0"
      switch_name = var.switch_name
      mac_address = each.value.mac_upper
    },
  ]

  hard_disk_drive = [
    {
      path                = hyperv_vhd.os[each.key].path
      controller_type     = "SCSI"
      controller_number   = 0
      controller_location = 0
    },
    {
      path                = hyperv_vhd.data[each.key].path
      controller_type     = "SCSI"
      controller_number   = 0
      controller_location = 1
    },
  ]

  dvd_drive = [
    {
      iso_path            = hyperv_image_file.seed[each.key].destination_path
      controller_type     = "SCSI"
      controller_number   = 0
      controller_location = 2
    },
  ]

  boot_order = [
    { type = "hard_disk_drive", controller_number = 0, controller_location = 0 },
  ]
}

# --- settings hyperv_vm (windsorcli/hyperv 0.4.0) does not expose, plus the VM's actual start --
#
# Checkpoint type, automatic start/stop action, and integration-service
# toggles are not arguments of hyperv_vm in this provider version -- its own
# docs say outright: "Integration services, automatic start/stop actions,
# and checkpoints are not currently exposed." The prior taliesins/hyperv
# configuration set all of these inline; asserting them here, once per VM
# via the Hyper-V PowerShell module directly (not the Terraform/OpenTofu
# provider), is the explicit safeguard for the one setting that is
# safety-critical for this cluster:
#
#   checkpoint_type = Disabled -- restoring a checkpoint of an etcd member
#   rewinds its log and can corrupt the cluster; rebuild the VM instead.
#   A newly created Hyper-V VM defaults to "Production" checkpoints.
#
# CONFIRMED, not hypothetical (2026-09-26): checkpoint type alone is not
# enough. "Automatic Checkpoints" is a SEPARATE Hyper-V setting (on by
# default) that takes a checkpoint at every VM start regardless of
# checkpoint type, until AutomaticCheckpointsEnabled is turned off. With
# hyperv_vm.vm created already Running (an earlier revision of this file),
# two lab VMs each took an automatic checkpoint at their first boot -- before
# this null_resource ever got a chance to disable checkpoints -- silently
# turning hard_disk_drive.path into a GUID-suffixed .avhdx (an active
# differencing disk), which the provider then reported as "Provider produced
# inconsistent result after apply". Fixed by (a) hyperv_vm.vm now creating
# the VM powered OFF (state.desired = "Off", above) and (b) this resource
# both disabling Automatic Checkpoints AND starting the VM itself, in that
# order, so a checkpoint can never be taken before checkpoints are disabled.
#
# automatic_start_action/automatic_stop_action and the integration-service
# set are asserted in the same call for parity with what taliesins/hyperv
# declared before, not because they are safety-critical the way checkpoints
# are.
#
# KNOWN LIMITATION, not silently assumed away: this runs once, when the VM
# is created or replaced (the trigger is the VM's id) -- it does NOT detect
# or correct drift afterward. If checkpoint type, Automatic Checkpoints, or
# these other settings are changed out-of-band later (Hyper-V Manager, a
# backup product, Windows Update), this resource has no way to notice.
# Re-run `tofu apply -replace='null_resource.vm_unmanaged_settings["<name>"]'`,
# or add a periodic drift check outside Terraform/OpenTofu, if that risk
# matters for this lab.
resource "null_resource" "vm_unmanaged_settings" {
  for_each = local.nodes

  triggers = {
    vm_id = hyperv_vm.vm[each.key].id
  }

  provisioner "local-exec" {
    interpreter = ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command"]
    command     = <<-EOT
      $ErrorActionPreference = "Stop"
      Set-VM -Name '${each.key}' -CheckpointType Disabled -AutomaticCheckpointsEnabled $false -AutomaticStartAction Start -AutomaticStopAction ShutDown
      Disable-VMIntegrationService -VMName '${each.key}' -Name 'Guest Service Interface'
      Enable-VMIntegrationService -VMName '${each.key}' -Name 'Heartbeat', 'Key-Value Pair Exchange', 'Shutdown', 'Time Synchronization', 'VSS'
      Start-VM -Name '${each.key}'
    EOT
  }
}
