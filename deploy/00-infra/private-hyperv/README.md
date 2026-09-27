# Standalone Layer 0: Hyper-V lab machines

This directory can be copied into another project and run from there. It
prepares one Windows Hyper-V host, then creates Ubuntu Server VMs with
OpenTofu. It does not install Kubernetes, depend on a sibling directory, or
require any IOT-EE service. The default node names, addresses, and disk sizes
are an example lab profile; change them in `terraform.tfvars` for your project.
This is a single-host lab, not a high-availability or production design.

The directory owns its inputs (`terraform.tfvars.example`), host preparation
(`scripts/`), OpenTofu configuration and provider lock, tests (`tests/`), and
outputs. State, plans, real tfvars, SSH keys, downloaded tools, and images must
stay out of Git. Copy the entire directory, including `.terraform.lock.hcl`,
`.gitignore`, `scripts/StoragePreflight.ps1`, and `tests/verify.ps1`.

## What it creates

The host-prep script checks disk headroom, enables Hyper-V if necessary,
creates an Internal switch, gateway, Windows NAT, directories, and an immutable
Ubuntu cloud-image VHDX template. It can install QEMU to convert the image.
The OpenTofu configuration creates, for each configured node, a cloud-init
ISO, a differencing OS VHDX, a dynamic data VHDX, and a Generation 2 VM.
After creating each VM powered off, it disables checkpoints and automatic
checkpoints, configures integration services, and starts it. The cloud-init
seed sets the hostname, static IP, and a public-key-only `admin_user` login.

OpenTofu uses `registry.terraform.io/windsorcli/hyperv` 0.4.0 with its local
backend and `hashicorp/null` 3.3.2. Run it **on the Hyper-V host** in an
elevated PowerShell session. It does not need a WinRM account or listener.
The `null_resource` settings run when a VM is created or replaced; OpenTofu
does not detect later out-of-band changes to those Hyper-V settings. Check
checkpoint settings again after host changes or before putting etcd on a VM.

## Prerequisites

- Windows with Hyper-V and an elevated PowerShell 5.1+ session whose account
  can administer Hyper-V and write to the selected VM/template directories.
- OpenTofu 1.6+ on `PATH` and network access for provider installation on the
  first `tofu init`. The pinned lock file is part of this directory.
- A local disk volume with enough free space for the selected node disks,
  template, image download, and future growth. The example asks for 280 GB
  for VMs and 40 GB for the template; if both roots share a drive the script
  requires 320 GB free. Size these thresholds for your own `nodes` map.
- An SSH public key. `ssh-keygen -t ed25519` can create one if needed; never
  put the private key or real `terraform.tfvars` in Git.
- No conflicting Windows NAT prefix. The script refuses to replace another
  NAT. It can install QEMU for Windows automatically; `-SkipQemuInstall` and
  `-QemuImgPath` allow you to provide a trusted binary instead.

## Install from this directory

Open an elevated PowerShell and change into this directory. Choose an actual
local drive with enough free space; `X:` in the example tfvars is a placeholder.
Use the **same paths, switch name, subnet, and gateway** in both commands and
the copied tfvars. The following uses `E:` only as an illustration:

```powershell
Copy-Item .\terraform.tfvars.example .\terraform.tfvars
# Edit terraform.tfvars: replace X: and set your SSH public key path and nodes.
.\scripts\prep-hyperv-host.ps1 -VmRoot 'E:\HyperV\lab\vms' -TemplateRoot 'E:\HyperV\lab\templates' -WhatIf
.\scripts\prep-hyperv-host.ps1 -VmRoot 'E:\HyperV\lab\vms' -TemplateRoot 'E:\HyperV\lab\templates'
tofu init -input=false
tofu plan -out layer0.tfplan
tofu show layer0.tfplan
# Apply the reviewed plan only after checking VM names, disk paths and actions.
tofu apply layer0.tfplan
```

Adjust `-VmFreeGB` and `-TemplateFreeGB` only after sizing the desired lab.
Re-running prep is safe for resources it already created, but it still checks
current free space. A completed template is read-only; build a new one with
`-TemplateName` and update `template_vhdx_path` instead of modifying its parent
in place. `-ApiServerForwardTo <control-plane IP>` is optional and opens host
TCP 6443 to the LAN; leave it unset unless that access is needed.

For the example's three nodes, a fresh plan has 15 managed resources: three
ISOs, six disks, three VMs, and three VM settings/start safeguards. A different
`nodes` map changes the count. Never apply a plan that unexpectedly destroys
VMs or disks. Keep a copy of state and any data-bearing VHDX before a rebuild.

## Verify and hand off

Run the directory's own checks without provisioning anything:

```powershell
.\tests\verify.ps1           # PowerShell 5.1+, offline static and capacity tests
.\tests\verify.ps1 -RunTofu  # also tofu fmt, init, validate; downloads providers
```

After apply, confirm the actual VM state and guest access:

```powershell
$names = (tofu output -json nodes | ConvertFrom-Json).PSObject.Properties.Name
Get-VM -Name $names | Select-Object Name,State,CheckpointType,AutomaticCheckpointsEnabled
tofu output -raw ansible_inventory_ini | Set-Content -Encoding utf8 .\hosts.ini
tofu output -raw nodes_json
```

`hosts.ini` is ignored locally. Move it to the destination chosen by your
configuration-management project. The other outputs are `nodes`,
`control_plane_ips`, `worker_ips`, `network`, `ansible_inventory` (YAML), and
`ansible_inventory_json`. The inventory groups use the conventional names
`rke2_server`, `rke2_agent`, and `rke2_cluster`; consumers can transform them.
No Layer 1 path is required to validate or apply this Layer 0 directory.

## Verification record

The project owner reported a successful 15-resource apply on a Hyper-V lab
host on 2026-09-27, followed by three running VMs with checkpoints disabled
and a successful RKE2 installation in a separate Layer 1. That live run used
site-specific ignored tfvars on `E:`; the portable copy and its new tests
still require their own CI check. The first `D:` run exhausted disk space,
which is why host prep now checks capacity before modifying the host.
