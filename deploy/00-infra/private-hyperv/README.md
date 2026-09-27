# Standalone Layer 0: Hyper-V lab machines

This directory is a self-contained module. It prepares one Windows Hyper-V
host and creates Ubuntu Server VMs with OpenTofu, then hands the node list
to whatever configures them. It does not install Kubernetes, read from or
write into any other directory, or need any IOT-EE service; you can copy the
whole directory into another project and run it there. The node names,
addresses and disk sizes in `terraform.tfvars.example` are an example lab.
This is a **single-host lab**, not a highly available or production design.

| | |
| --- | --- |
| **Needs** | A Windows Hyper-V host, an elevated PowerShell 5.1+ session in *Hyper-V Administrators*, OpenTofu 1.6+, network access to the Ubuntu cloud-image site and the provider registries on first use, an SSH public key, and this directory's `terraform.tfvars` |
| **Creates** | On the host (prep script): an Internal switch, gateway address, Windows NAT, VM and template folders, a read-only golden template, optionally QEMU for Windows. Per node (OpenTofu): a cloud-init ISO, a differencing OS disk, a dynamic data disk, a Generation 2 VM |
| **Produces** | `out/ansible-inventory.ini`, `out/nodes.json`, `out/network.json` and `out/check-*.json` reports (git-ignored); the same data as `tofu output` |
| **One source of settings** | `terraform.tfvars`. `scripts/deploy-layer0.ps1` and `scripts/check-layer0.ps1` read it through `tofu console`, so host preparation gets exactly the paths, switch, subnet, gateway and sizes the plan uses |
| **One test command** | `.\tests\verify.ps1` (offline; add `-RunTofu` for `tofu fmt`/`init`/`validate`). No Hyper-V needed |
| **Owns** | Inputs (`terraform.tfvars.example`), host preparation and helpers (`scripts/`), OpenTofu configuration and provider lock, tests (`tests/`), outputs. Copy the whole directory, including `.terraform.lock.hcl` and `.gitignore` |
| **Keeps out of Git** | `terraform.tfvars`, state, saved plans, `out/`, `hosts.ini`, VM disks and images, downloaded tools |

## What it creates

`scripts/prep-hyperv-host.ps1` first checks disk capacity, then enables
Hyper-V if necessary, creates the Internal switch, gateway, Windows NAT,
folders and an immutable Ubuntu cloud-image VHDX template (verified against
Canonical's `SHA256SUMS`). It can install QEMU for Windows to convert the
image. It never creates a WinRM listener.

The OpenTofu configuration creates, for each node, a cloud-init ISO, a
differencing OS VHDX, a dynamic data VHDX and a Generation 2 VM. Each VM is
created **powered off**; a `null_resource` then disables checkpoints and
Automatic Checkpoints, sets integration services and start/stop actions,
and starts it. The seed sets only the hostname, a static IP and one
public-key-only `admin_user` login.

OpenTofu uses `registry.terraform.io/windsorcli/hyperv` 0.4.0 (MPL-2.0) with
its local backend and `hashicorp/null` 3.3.2, both pinned exactly; the lock
file has hashes for `windows_amd64` and `linux_amd64`. Run it **on the
Hyper-V host**; it needs no WinRM account, listener or credentials.

## Network (example values)

```
 LAN ── host NIC ── Windows host ── WinNAT (10.20.0.0/24)
                          │
                   vEthernet (iotee-nat) 10.20.0.1   <- the VMs' gateway
                          │
             Internal switch "iotee-nat"
          ┌───────────────┼───────────────┐
   rke2-master-01   rke2-worker-01   rke2-worker-02
    10.20.0.10        10.20.0.21       10.20.0.22
```

The VMs reach the internet through NAT; DNS comes from `network.dns_servers`.
They are reachable **from the host only** unless you publish a port
(`-ApiServerForwardTo <control-plane IP>` forwards host TCP 6443). Windows
allows **one NAT per host**; the prep script refuses to replace another.

## Disk space

OS disks are differencing disks and data disks are dynamic: both start small
and **grow** as the guests write, each OS disk up to the template size (30 GB)
and each data disk up to its `data_disk_gb`. The example nodes can reach
3 x 30 + 40 + 60 + 60 = 250 GB. When the volume fills, Hyper-V pauses the VMs
("Disk Full") and then turns them off. That stopped both workers of the
first lab mid-install on 2026-09-27, which is why capacity is checked first.

- `prep-hyperv-host.ps1` on its own requires `-VmFreeGB` (default 280) and
  `-TemplateFreeGB` (default 40); on one volume the two are added.
- `deploy-layer0.ps1` passes what the lab still needs instead: the worst case
  minus what the existing Layer 0 disks already occupy, at least `-MinFreeGB`
  (40), and 1 GB for the template once it is built. `-AllowOvercommit`
  requires only `-MinFreeGB` and prints the shortfall.
- `check-layer0.ps1` fails below `-MinFreeGB` and warns below the worst case.

## Install

Elevated PowerShell on the Hyper-V host, in this directory:

```powershell
Copy-Item .\terraform.tfvars.example .\terraform.tfvars
# Edit terraform.tfvars: replace X: with a real local volume, set ssh_public_key_path and nodes.
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\deploy-layer0.ps1 -PlanOnly   # preflight, capacity, host prep, plan; applies nothing
.\scripts\deploy-layer0.ps1             # the same, then asks you to type "apply"
```

`deploy-layer0.ps1` runs: preflight (Hyper-V module, OpenTofu version,
`terraform.tfvars`); `tofu init -lockfile=readonly`; the settings from
`terraform.tfvars` through `tofu console` (variable validations apply, and
their failures stop the run even though `tofu console` exits 0); the
capacity check and `prep-hyperv-host.ps1`, which asks before each change; a
gate that stops if the switch or the read-only template is missing (for
example after enabling Hyper-V, which needs a reboot); a saved plan with a
create/update/delete summary; **apply only after you type `apply`**; the
read-only `check-layer0.ps1`, waiting up to `-WaitMinutes` (5) for the
guests; and the hand-over files in `out/`. It refuses a plan that deletes or
replaces anything unless you pass `-AllowDestroy`. `-SkipHostPrep` skips the
prep script but not the capacity check. Re-running is safe: an unchanged lab
plans "No changes" and goes straight to the check.

For three nodes, a first plan has **15** resources: three ISOs, six disks,
three VMs and three settings steps. Never apply a plan that unexpectedly
destroys VMs or disks.

<details>
<summary>The same steps by hand</summary>

```powershell
.\scripts\prep-hyperv-host.ps1 -VmRoot 'E:\HyperV\lab\vms' -TemplateRoot 'E:\HyperV\lab\templates' -WhatIf
.\scripts\prep-hyperv-host.ps1 -VmRoot 'E:\HyperV\lab\vms' -TemplateRoot 'E:\HyperV\lab\templates'
tofu init -input=false -lockfile=readonly
tofu plan -out layer0.tfplan
tofu show layer0.tfplan
tofu apply layer0.tfplan
.\scripts\check-layer0.ps1 -WaitMinutes 5
New-Item -ItemType Directory -Force out | Out-Null
tofu output -raw ansible_inventory_ini | Set-Content -Encoding ascii out\ansible-inventory.ini
```

Use the same paths, switch, subnet and gateway as `terraform.tfvars`. Write
the inventory with `Set-Content -Encoding ascii`, not `>`: in Windows
PowerShell 5.1 `>` writes UTF-16, which Ansible cannot read.
</details>

## Check and hand over

```powershell
.\scripts\check-layer0.ps1                  # read-only; exit code 1 on any failure
.\scripts\check-layer0.ps1 -CheckCloudInit  # plus `cloud-init status` over SSH
```

Per node it checks: the VM exists and is Running; checkpoints are off (type,
Automatic Checkpoints, no snapshots, no `.avhdx`); every disk is under
`vm_root`; the Heartbeat integration service is OK; TCP 22 answers. With
`-CheckCloudInit` it runs `cloud-init status` over SSH with your key and
**already trusted** host keys only (it never accepts a host key). It also
checks free space, and writes `out/check-<UTC time>.json`.

"Port 22 refused while the heartbeat is OK" means the guest is up but SSH
never started. On the first lab that was a VM whose OS disk was left over
from an earlier, interrupted boot; rebuilding it (below) fixed it.

**Hand-over contract:** `out/ansible-inventory.ini` has groups
`rke2_server` (control-plane nodes), `rke2_agent` (workers) and
`rke2_cluster` (both), `ansible_host` per node, and `ansible_user` /
`ansible_python_interpreter` for the group. `out/nodes.json` and
`out/network.json` carry the same nodes and the network. A consumer copies
these files into its own configuration; this directory never writes
anywhere else. The other outputs are `nodes`, `control_plane_ips`,
`worker_ips`, `network`, `ansible_inventory` (YAML) and
`ansible_inventory_json`.

## Changing the lab

- **Add a worker:** add an entry to `nodes` (unique name, `ip_host`, `mac`),
  run `deploy-layer0.ps1`, then hand the new inventory to the consumer.
- **Remove a node:** drain it in the consumer first, remove its entry, run
  `deploy-layer0.ps1 -AllowDestroy` and review the plan.
- **Rebuild a node** (fresh OS and data disks; the VM is replaced too, so
  disks are never deleted while attached). `--%` keeps the quotes intact in
  PowerShell:
  ```powershell
  tofu plan -out rebuild.tfplan --% -replace=hyperv_vm.vm[\"rke2-worker-01\"] -replace=hyperv_vhd.os[\"rke2-worker-01\"] -replace=hyperv_vhd.data[\"rke2-worker-01\"]
  ```
  Expect 4 to add and 4 to destroy, all for that node. Apply only that
  plan, then remove the node's old host key (`ssh-keygen -R <address>`).
- **Move to another volume:** `tofu plan -destroy -out destroy.tfplan`,
  review, apply; copy the read-only template to the new template folder;
  update `terraform.tfvars`; run `deploy-layer0.ps1`. Everything on the VMs
  is lost.
- **Remove the lab:** the same destroy plan. The template, switch and NAT
  stay (host preparation, reusable).

## Design notes

- **No checkpoints.** Restoring a checkpoint of an etcd member rewinds its
  log and can corrupt the cluster. The settings step runs when a VM is
  created or replaced; OpenTofu does not see later out-of-band changes, so
  `check-layer0.ps1` checks them.
- **Differencing OS disks** over a read-only template: fast to create, cheap
  to rebuild. Build a new template with `-TemplateName` and point
  `template_vhdx_path` at it instead of changing a parent in place.
- **A map, not a count,** for `nodes`: removing one VM never renumbers or
  rebuilds the others.
- **No secrets.** No connection credentials; only the SSH *public* key is
  read, and a private key path is rejected by validation.

## Provider history

Until 2026-09-26 this module used `taliesins/hyperv` 1.2.1 over WinRM/HTTPS
with a local service account. On the lab host that connection could not be
authorized even with the account in *Hyper-V Administrators* and *Remote
Management Users*, so it moved to `windsorcli/hyperv` 0.4.0 with the local
backend (published on `registry.terraform.io` only, hence the explicit
hostname in `main.tf`). Resource names changed (`hyperv_machine_instance` ->
`hyperv_vm`, `hyperv_iso_image` -> `hyperv_image_file` plus the
`hyperv_iso_volume` data source). A WinRM listener, the firewall rule
`IOT-EE WinRM HTTPS (Terraform)` or an `iotee-tf` account left by an older
prep run are unused now; remove them if nothing else needs them.

## Verification record

- **Offline** (2026-09-27): `tests/verify.ps1 -RunTofu` passes: every script
  parses; the capacity rules accept 3 and reject 4 cases; the directory has
  no reference outside itself; the deploy/check helper unit tests (33
  offline, 37 with the real `tofu console`); `tofu fmt`, locked `init` and
  `validate` against the real provider archives, whose SHA-256 values match
  the lock file. `deploy-layer0.ps1` and `check-layer0.ps1` were exercised on
  Linux with the Hyper-V cmdlets stubbed (plan-only, declined confirmation,
  capacity refusal and overcommit; missing VM, snapshot, `.avhdx`, closed SSH
  port). **Neither script has run on a Hyper-V host yet.**
- **Real lab run** (2026-09-27, owner's host, commands run by the owner and
  their output reviewed): with site-specific tfvars on `E:`, `tofu apply`
  added 15 resources; three VMs ran with checkpoints disabled and answered
  SSH (cloud-init `status: done`, `errors: []` was checked on the previous
  VMs built from the same template and seed); a separate Layer 1
  then installed RKE2 with all three nodes Ready. The first attempt on `D:`
  ran out of disk space.
- Not verified: a host restart, adding or removing a node, any restore, and
  anything beyond one host.
