# `deploy/`: private-cloud infrastructure

Infrastructure-as-code for the IOT-EE private cloud: an HA **RKE2** cluster on
**Hyper-V** VMs across two Windows hosts, with **kube-vip** for the API
virtual IP and bare-metal LoadBalancer services. The decisions behind it are
recorded in `docs/adr/XXXX-proposed-private-cloud-infrastructure.md`.

This is separate from the Java build: nothing here is read by Maven, and the
application gates (`mvn verify`, `python spec/scripts/check.py`) do not cover
it. It has its own static gate, `.github/workflows/infra.yml`.

| Layer | Path | Tool | Owns |
| --- | --- | --- | --- |
| Provisioning | `provisioning/hyperv-host/` | PowerShell | One-time host prep: Hyper-V, external vSwitch, WinRM, golden image |
| Provisioning | `provisioning/terraform/` | OpenTofu / Terraform | VMs, disks, cloud-init seed, generated Ansible inventory |
| Configuration | `configuration/` | Ansible | OS settings, RKE2 install/join, kube-vip, health checks, healing |
| Cluster add-ons | `k8s/` | Kustomize | kube-vip-cloud-provider and the LoadBalancer address pool |

Terraform knows nothing about Kubernetes; Ansible knows nothing about Hyper-V
beyond the inventory Terraform writes for it.

## Topology limits: read before relying on this for HA

- **Two physical hosts cannot survive the loss of one host.** etcd needs a
  majority of its members. With 3 servers split 2+1, losing the host with 2
  loses quorum; with all 3 on one host, losing that host loses everything. The
  default (3 servers on the OptiPlex, agents on the laptop) is HA against a
  VM or process failure, not a host failure. Host-level HA needs a third
  physical host with one server on each.
- **Keep servers on the wired host.** Hyper-V bridges Wi-Fi by rewriting MAC
  addresses, so kube-vip's ARP-announced VIP is unreliable behind the
  laptop's Wi-Fi switch. Agents there are fine: they reach the VIP, they
  don't hold it.
- **RKE2 minimum per server:** 2 vCPU, 4 GB RAM (Terraform rejects less).

## Prerequisites

On each Hyper-V host (Windows, admin PowerShell):
- Hyper-V capable edition (Pro/Enterprise/Education).
- QEMU for Windows (`qemu-img`), for converting the Ubuntu cloud image.

On the operator workstation (or a self-hosted runner on the LAN):
- OpenTofu >= 1.6 (or Terraform >= 1.6), Ansible (ansible-core), kubectl.
- An SSH key pair; the public key goes into `terraform.tfvars`.

Secrets, never committed:
- `TF_VAR_hyperv_user`, `TF_VAR_hyperv_password`: a Windows account in
  *Hyper-V Administrators* on both hosts.
- `RKE2_TOKEN`: the cluster join secret, e.g. `openssl rand -hex 32`. Store
  it in your password vault and reuse the same value for the life of the
  cluster; new nodes and etcd restores need it.

## Sequence of operations

### 1. Prepare each Hyper-V host (once per host)

```powershell
cd deploy\provisioning\hyperv-host
.\Initialize-HyperVHost.ps1 -NetAdapterName 'Ethernet' -VmRoot 'D:\HyperV\iotee' -ImageRoot 'D:\HyperV\images' -AllowedRemoteAddress 192.168.1.50
.\New-BaseImage.ps1 -ImageRoot 'D:\HyperV\images'
```

`Initialize-HyperVHost.ps1` asks before every change (`-WhatIf` to preview).
Creating the external switch briefly drops the host's network connection.
`New-BaseImage.ps1` verifies the Ubuntu image against Canonical's SHA256SUMS
and writes a dated, read-only VHDX. Never edit a golden image in place: every
VM's disk is a differencing child of it.

### 2. Provision the VMs

```bash
cd deploy/provisioning/terraform
cp terraform.tfvars.example terraform.tfvars   # set hosts, network, keys, nodes
export TF_VAR_hyperv_user='HOST\iotee-tf' TF_VAR_hyperv_password='...'
tofu init
tofu plan -out plan.tfplan     # review: VMs, disks and seed ISOs per node
tofu apply plan.tfplan
```

Each node gets a differencing disk over the golden image, a cloud-init seed
ISO (hostname, static IP matched to its static MAC, SSH key, passwordless
sudo, no password login) and a Gen-2 VM with Secure Boot and checkpoints
disabled (restoring a checkpoint of an etcd member corrupts the cluster).
Apply also writes `configuration/inventory/generated/hosts.yml`.

After the first `tofu init`, commit `.terraform.lock.hcl` with hashes for
every platform that runs Terraform:
`tofu providers lock -platform=windows_amd64 -platform=linux_amd64`.

### 3. Bootstrap RKE2 with Ansible

```bash
cd deploy/configuration
for ip in $(grep ansible_host inventory/generated/hosts.yml | awk '{print $2}'); do
  ssh-keyscan -H "$ip" >> ~/.ssh/known_hosts      # verify fingerprints on first use
done
export RKE2_TOKEN='...'                            # same value every run
ansible-playbook playbooks/bootstrap.yml
```

What it does:
1. **Every node:** waits for cloud-init, turns swap off, loads `overlay` and
   `br_netfilter`, sets the Kubernetes sysctls, keeps time in sync.
2. **Servers, one at a time:** writes the kube-vip static pod, installs RKE2
   `rke2_version` (pinned in `playbooks/group_vars/all.yml`), and either
   initializes the cluster (first server, no VIP answering) or joins through
   the VIP. A rebuilt first server joins; it never starts a second cluster.
   RKE2 brings its own containerd, so there is no separate runtime to install.
3. **Agents:** install RKE2 in agent mode and join through the VIP.
4. Writes an admin kubeconfig pointing at the VIP to
   `configuration/.kube/<cluster_name>.yaml` (git-ignored).

### 4. Cluster add-ons: LoadBalancer services

```bash
export KUBECONFIG=$PWD/.kube/iotee-pc.yaml
kubectl apply -k ../k8s
kubectl -n kube-system rollout status deploy/kube-vip-cloud-provider
```

Smoke test: `kubectl create deployment web --image=nginx`, then
`kubectl expose deployment web --port 80 --type LoadBalancer`, then
`kubectl get svc web`. The EXTERNAL-IP should come from
`k8s/kube-vip/kubevip-address-pool.yaml` and answer on the LAN.

## Day 2

### Scaling (declared in Git)

There is no Cluster Autoscaler: it needs a cloud or Cluster API provider,
and Hyper-V has none. Node count lives in the `nodes` map in
`terraform.tfvars`:

- **Add a node:** add an entry (unique name, IP, MAC), `tofu apply`, then
  `ansible-playbook playbooks/bootstrap.yml --limit <node>`.
- **Remove a node:** drain it (below), `kubectl delete node <node>`, remove the
  entry, `tofu apply`.
- Keep 1, 3 or 5 servers (Terraform enforces it).

A map, not a count, so adding or removing one node never renumbers or
rebuilds the others.

### Health checks (read-only, safe to schedule)

```bash
ansible-playbook playbooks/health.yml
```

Checks per node: the RKE2 service is running, the Kubernetes Ready condition,
root filesystem free space and available memory. For the cluster, it checks
API `/readyz` through the VIP. Writes a JSON report to `configuration/reports/`
and exits non-zero if anything is unhealthy, so a cron job or CI schedule can
alert on it.

### Healing (human-approved)

`heal.yml` does nothing without `-e heal_confirm=yes`. Without it, it prints
the plan. It also requires `--limit` and acts on one node at a time.

```bash
ansible-playbook playbooks/heal.yml --limit rke2-agent-2 -e heal_action=restart                    # plan
ansible-playbook playbooks/heal.yml --limit rke2-agent-2 -e heal_action=restart -e heal_confirm=yes
ansible-playbook playbooks/heal.yml --limit rke2-agent-2 -e heal_action=drain   -e heal_confirm=yes
ansible-playbook playbooks/heal.yml --limit rke2-agent-2 -e heal_action=uncordon -e heal_confirm=yes
ansible-playbook playbooks/heal.yml --limit rke2-agent-2 -e heal_action=replace   # prints rebuild steps
```

The automatic layer is systemd's restart policy on the RKE2 units. Anything
beyond that (restarting, draining, rebuilding a VM) is an infrastructure
action and needs a human's explicit go-ahead, per the development contract.
Replacing a node is never automatic: `heal_action=replace` prints the drain,
`kubectl delete node`, `tofu apply -replace=...`, and re-bootstrap steps.

### Upgrades

Change `rke2_version` in `configuration/playbooks/group_vars/all.yml` in a PR,
then run `bootstrap.yml` for the servers first (`--limit rke2_servers`), then
the agents. Upgrade one minor version at a time.

## Verification status

What the `infra.yml` CI gate checks: `tofu fmt`/`validate`, Ansible
`--syntax-check`, PowerShell parsing, and kustomize render + kubeconform.
While this was being written, `tofu validate` and an offline `tofu plan`
(16 resources for the example node map) ran against the real provider
binaries, and every variable validation was exercised.

**Not yet exercised against real hosts:** no VM has been created and no
cluster bootstrapped from this code. Treat the first run on the hosts as the
real test, one step at a time, and record the result here.
