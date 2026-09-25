# Layer 0: private Hyper-V infrastructure

Raw virtual machines for the IOT-EE Kubernetes cluster, on **one Windows
Hyper-V host**, behind a **private (Internal) switch with Windows NAT**.

| VM | Role | IPv4 | MAC | vCPU | RAM | Data disk |
| --- | --- | --- | --- | --- | --- | --- |
| `rke2-master-01` | control-plane | 10.20.0.10 | 00155D140A10 | 2 | 4 GB | 40 GB |
| `rke2-worker-01` | worker | 10.20.0.21 | 00155D140A21 | 2 | 4 GB | 60 GB |
| `rke2-worker-02` | worker | 10.20.0.22 | 00155D140A22 | 2 | 4 GB | 60 GB |

These are the defaults; every value is a variable (`variables.tf`).

## Scope: what Layer 0 does and does not do

**Does:** the Internal switch, NAT, gateway address and golden template on the
host (`scripts/prep-hyperv-host.ps1`); per VM, a differencing OS disk, an
empty data disk, a Gen-2 VM with a static MAC, and a cloud-init seed that sets
only the **hostname, static IP, and one login with one SSH public key**.
Outputs the node list as JSON and as an Ansible inventory for Layer 1.

**Does not:** install packages, run commands in the guests, format the data
disk, or touch Kubernetes. That is Layer 1 (`deploy/01-k8s-engine/rke2-ansible`, Ansible)
and Layer 2 (`deploy/k8s`).

The seed is required, not optional: Hyper-V cannot set a Linux guest's IP from
the host, and Windows NAT has no DHCP server, so the guest must apply its own
static address on first boot.

## Network

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

| Address(es) | Used for |
| --- | --- |
| 10.20.0.1 | Host (gateway, NAT) |
| 10.20.0.10-.22 | Nodes |
| 10.20.0.40-.49 | LoadBalancer services (Layer 2, kube-vip) |

- The VMs reach the internet through NAT. DNS comes from `network.dns_servers`
  (NAT provides none).
- The VMs are reachable **from the host only**. Publish a port to the LAN with
  a NAT static mapping. The prep script can do it for the API:
  `-ApiServerForwardTo 10.20.0.10`. Add the host's LAN address to Layer 1's
  `rke2_tls_san` so the API certificate is valid for it.
- Windows allows **one NAT per host**. The prep script refuses to run if a
  different NAT exists (for example Docker Desktop's) rather than replace it.

## Prerequisites (on the Hyper-V host)

- Windows 10/11 Pro/Enterprise/Education or Windows Server with Hyper-V, and
  about 16 GB free RAM and 200 GB free disk for the defaults.
- QEMU for Windows (`qemu-img.exe`), to convert the Ubuntu cloud image.
- OpenTofu >= 1.6 (or Terraform >= 1.6).
- An SSH key pair: `ssh-keygen -t ed25519` creates `~/.ssh/id_ed25519.pub`.
- A local Windows account in *Hyper-V Administrators* for Terraform, e.g.:
  ```powershell
  $pw = Read-Host -AsSecureString "Password for iotee-tf"
  New-LocalUser -Name iotee-tf -Password $pw -PasswordNeverExpires
  Add-LocalGroupMember -Group "Hyper-V Administrators" -Member iotee-tf
  Add-LocalGroupMember -Group "Remote Management Users" -Member iotee-tf
  ```
  After step 1 below has created `D:\HyperV\iotee`, give that account write
  access to it (Terraform creates the disks and seed ISOs there):
  ```powershell
  icacls D:\HyperV\iotee /grant "iotee-tf:(OI)(CI)M"
  ```

## Run it

All commands run in an **elevated PowerShell** on the Hyper-V host, from the
repository root.

### 1. Prepare the host (once)

```powershell
cd deploy\00-infra\private-hyperv
.\scripts\prep-hyperv-host.ps1 -WhatIf     # preview every change
.\scripts\prep-hyperv-host.ps1             # asks before each change
```

Idempotent: re-running changes nothing that is already in place. The golden
template (`D:\HyperV\iotee\templates\ubuntu-noble-base.vhdx`) is marked
read-only and never rebuilt in place. To roll a new one, pass
`-TemplateName ubuntu-noble-base-v2.vhdx` and point `template_vhdx_path` at it.

### 2. Configure

```powershell
Copy-Item terraform.tfvars.example terraform.tfvars   # git-ignored; edit if your paths differ
$env:TF_VAR_hyperv_user     = "$env:COMPUTERNAME\iotee-tf"
$secure = Read-Host -AsSecureString "Password for iotee-tf"
$env:TF_VAR_hyperv_password = [System.Net.NetworkCredential]::new('', $secure).Password
```

This works in both Windows PowerShell 5.1 and PowerShell 7, and the password
stays in this session's environment only.

### 3. Plan and apply

```powershell
tofu init
tofu plan -out l0.tfplan      # expect 12 to add: per VM a seed ISO, 2 disks, 1 VM
tofu apply l0.tfplan
```

After the first `tofu init`, commit the provider lock file:
`tofu providers lock -platform=windows_amd64 -platform=linux_amd64`.

### 4. Hand over to Layer 1

```powershell
tofu output nodes_json
tofu output -raw ansible_inventory_ini | Set-Content -Encoding utf8 ..\..\01-k8s-engine\rke2-ansible\inventory\hosts.ini
```

With the default `nodes` and `network` the generated file equals the committed
`inventory/hosts.ini`, so `git diff` shows nothing. Then continue with Layer 1
in `deploy/01-k8s-engine/rke2-ansible/README.md`.

## Outputs

| Output | Content |
| --- | --- |
| `nodes` | per VM: role, IPv4, MAC, vCPU, RAM, data disk, Hyper-V name |
| `nodes_json` | role, IPv4 and MAC per VM, as a JSON string |
| `control_plane_ips`, `worker_ips` | lists of IPv4 addresses |
| `ansible_inventory_ini` | INI inventory for Layer 1: groups `rke2_server` / `rke2_agent` / `rke2_cluster`, `ansible_host`, `ansible_user` |
| `ansible_inventory` | the same inventory as YAML |
| `ansible_inventory_json` | the same inventory as JSON |
| `network` | CIDR, gateway, DNS, switch name |

## Changing the cluster

- **Add a worker:** add an entry to `nodes` (unique name, `ip_host`, `mac`),
  `tofu apply`, re-export the inventory, then run Layer 1's `site-rke2.yml` for
  that node (with the first server in `--limit`, which supplies the join token).
- **Remove a node:** drain it in Layer 1 first, remove its entry, `tofu apply`.
- **Rebuild a node:**
  `tofu apply -replace='hyperv_vhd.os["rke2-worker-01"]' -replace='hyperv_vhd.data["rke2-worker-01"]' -replace='hyperv_machine_instance.vm["rke2-worker-01"]'`
- **Control-plane HA:** set 3 `control-plane` entries (Terraform accepts 1, 3
  or 5). One control-plane node means the API is down whenever that VM is.

## Design notes

- **No checkpoints.** Restoring a checkpoint of an etcd member rewinds its
  log and can corrupt the cluster; rebuild the VM instead.
- **Differencing OS disks** over a read-only template: fast to create, cheap
  to rebuild. The OS disk's size is the template's (`-TemplateSizeGB`, default
  30). Workload data goes on the separate, per-node-sized data disk.
- **A map, not a count,** for `nodes`: removing one VM never renumbers or
  rebuilds the others.
- **Secrets** (`TF_VAR_hyperv_*`) come from the environment. Only the SSH
  *public* key is read; a private key path is rejected by validation.

## Verification status

Checked while writing (2026-09-25): `tofu fmt` and `tofu validate` against the
real `taliesins/hyperv` 1.2.1 and `archive` provider binaries; an offline
`tofu plan` of the example (12 resources); the rendered seed files, the JSON
and YAML inventories parsed; every variable validation rejects bad input (node
count, MAC format, duplicates, gateway outside the subnet, node on the gateway,
undersized control plane, invalid hostname, private or missing SSH key); the
prep script parses with PowerShell 7.

**Not yet run on a real Hyper-V host.** Record the first real run here.
