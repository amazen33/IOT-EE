# `deploy/`: private-cloud infrastructure, in layers

Infrastructure-as-code for the IOT-EE private cloud: an **RKE2** Kubernetes
cluster on **Hyper-V** VMs on one Windows host, with **kube-vip** for LoadBalancer
services. The decisions behind it are recorded in
`docs/adr/XXXX-proposed-private-cloud-infrastructure.md`.

This is separate from the Java build: Maven never reads it, and the
application gates do not cover it. It has its own static CI gate,
`.github/workflows/infra.yml`, which cannot reach the host.

| Layer | Path | Tool | Owns | Hands over |
| --- | --- | --- | --- | --- |
| 0 | `00-infra/private-hyperv/` | PowerShell + OpenTofu/Terraform | Hyper-V switch, NAT, template; VMs, disks, static IPs | `ansible_inventory_ini`, `nodes_json` outputs |
| 1 | `01-k8s-engine/rke2-ansible/` | Ansible | OS prep, RKE2 engine (CIS profile, restricted PSS, secrets encryption), health checks, healing | admin kubeconfig |
| 2 | `k8s/` | Kustomize | kube-vip (services-only) + cloud-provider + LoadBalancer pool | - |

Each layer knows only the previous layer's outputs: Layer 0 knows nothing
about Kubernetes, and Layer 1 knows nothing about Hyper-V beyond the inventory.

## Topology and its limits

One Hyper-V host, one control-plane node, two workers, on the private
10.20.0.0/24 network behind Windows NAT (details and address plan in
`00-infra/private-hyperv/README.md`).

- **No control-plane HA.** When `rke2-master-01` is down, the API is down
  (running workloads keep running). Growing to 3 control-plane nodes is a
  Layer 0 variable change plus a Layer 1 run; clients then need re-pointing
  (or a VIP/DNS name added at that point), since the API is reached on the
  server's own address.
- **No host-level HA.** Everything is on one physical machine.
- **Host-only reachability.** The VMs, the API and LoadBalancer IPs are
  reachable from the Hyper-V host. See "Reaching services from the LAN".

## Sequence of operations

### Layer 0: VMs

Follow `00-infra/private-hyperv/README.md`: prepare the host once
(`scripts/prep-hyperv-host.ps1`), `tofu apply`, then export the inventory:

```powershell
cd deploy\00-infra\private-hyperv
tofu output -raw ansible_inventory_ini | Set-Content -Encoding utf8 ..\..\01-k8s-engine\rke2-ansible\inventory\hosts.ini
```

### Layer 1: the Kubernetes engine (RKE2, CIS-hardened)

Full guide: `01-k8s-engine/rke2-ansible/README.md`. From WSL 2 (Ubuntu) on the
Hyper-V host, in the repository:

```bash
sudo apt-get install -y ansible-core
cd deploy/01-k8s-engine/rke2-ansible
for ip in 10.20.0.10 10.20.0.21 10.20.0.22; do
  ssh-keyscan -H "$ip" >> ~/.ssh/known_hosts       # verify fingerprints on first use
done
export RKE2_TOKEN="$(openssl rand -hex 32)"         # first run only; store it in your vault
ansible-playbook site-rke2.yml
```

It prepares the OS on every node, installs RKE2 (pinned) with the CIS
profile, the restricted Pod Security Standard and secrets encryption on the
server, extracts the node token, joins the two agents with it, and writes the
admin kubeconfig to `~/.kube/config` (a previous one is backed up). Layer 1
installs nothing that runs *on* the cluster.

### Layer 2: add-ons

```bash
kubectl apply -k deploy/k8s
kubectl -n kube-system rollout status deploy/kube-vip-cloud-provider
kubectl -n kube-system rollout status ds/kube-vip-ds
```

Smoke test: `kubectl create deployment web --image=nginxinc/nginx-unprivileged`,
then `kubectl expose deployment web --port 80 --target-port 8080 --type LoadBalancer`.
`kubectl get svc web` should show an EXTERNAL-IP in 10.20.0.40-.49 that
answers from the host. (The stock `nginx` image runs as root and is rejected
by the restricted Pod Security Standard, which is the point.)

## Reaching services from the LAN

Everything sits behind the host's NAT. Publish what you need with a static
mapping (elevated PowerShell on the host):

- **Kubernetes API:** `.\scripts\prep-hyperv-host.ps1 -ApiServerForwardTo 10.20.0.10`.
  Then add the host's LAN address or name to `rke2_tls_san` and re-run
  `site-rke2.yml`, so the API certificate is valid for it.
- **A LoadBalancer service,** e.g. 10.20.0.40:80 published as host port 8080:
  ```powershell
  Add-NetNatStaticMapping -NatName iotee-nat -Protocol TCP -ExternalIPAddress 0.0.0.0 -ExternalPort 8080 -InternalIPAddress 10.20.0.40 -InternalPort 80
  New-NetFirewallRule -DisplayName "IOT-EE web 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
  ```

## Day 2

### Scaling (declared in Git)

There is no Cluster Autoscaler: it needs a cloud or Cluster API provider, and
Hyper-V has none. Node count lives in Layer 0's `nodes` map:

- **Add a worker:** add an entry, `tofu apply`, re-export the inventory, then
  `ansible-playbook site-rke2.yml --limit rke2-master-01,rke2-worker-03`.
- **Remove a node:** drain it (below), `kubectl delete node rke2-worker-02`,
  remove its entry, `tofu apply`.

### Health checks (read-only, safe to schedule)

```bash
ansible-playbook health.yml
```

Checks per node: the RKE2 service is running, the Kubernetes Ready condition,
root filesystem free space and available memory. For the cluster, it checks
API `/readyz` (via kubectl on the server). Writes a JSON report to `01-k8s-engine/rke2-ansible/reports/`
and exits non-zero if anything is unhealthy.

### Healing (human-approved)

`heal.yml` changes nothing without `-e heal_confirm=yes` (it prints the plan
instead), requires `--limit`, and acts on one node at a time:

```bash
ansible-playbook heal.yml --limit rke2-worker-02 -e heal_action=restart
ansible-playbook heal.yml --limit rke2-worker-02 -e heal_action=restart  -e heal_confirm=yes
ansible-playbook heal.yml --limit rke2-worker-02 -e heal_action=drain    -e heal_confirm=yes
ansible-playbook heal.yml --limit rke2-worker-02 -e heal_action=uncordon -e heal_confirm=yes
ansible-playbook heal.yml --limit rke2-worker-02 -e heal_action=replace
```

systemd restarts RKE2 automatically. Anything beyond that is an
infrastructure action and needs a human's explicit go-ahead, per the
development contract. `heal_action=replace` only prints the steps: drain,
`kubectl delete node`, a Layer 0 `tofu apply -replace=...` for the node's
disks and VM, then `site-rke2.yml` for it.

### Upgrades

Change `rke2_version` in `01-k8s-engine/rke2-ansible/inventory/group_vars/all.yml`
in a PR, then run `site-rke2.yml` for the server first (`--limit rke2_server`),
then the workers (`--limit rke2-master-01,rke2_agent`). Upgrade one minor version at a
time.

## Verification status

The `infra.yml` CI gate runs `tofu fmt` and `tofu validate` (Layer 0), Ansible
`--syntax-check` plus an `ansible.builtin`-only check (Layer 1), a PowerShell parse of the prep script, and a
kustomize render + kubeconform check (Layer 2). Layer 0 was also planned
offline while writing; see its README.

**Not yet exercised against a real host:** no VM has been created and no
cluster bootstrapped from this code. The first real run should go one layer at
a time; record the result here.
