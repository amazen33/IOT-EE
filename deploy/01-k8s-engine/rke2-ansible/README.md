# Layer 1 — `deploy/01-k8s-engine/rke2-ansible`

Ansible that turns Layer 0's three Ubuntu VMs into a **CIS-hardened RKE2
cluster**: one server (control plane + etcd) and two agents (workers). This
layer owns the raw Kubernetes engine **only**. Service meshes, Kafka, kube-vip,
ingress configuration and application workloads live in Layer 2
(`deploy/k8s`) and above, and must not be added here.

```
Layer 0  deploy/00-infra/private-hyperv   VMs, disks, static IPs  ──► inventory/hosts.ini
Layer 1  deploy/01-k8s-engine/rke2-ansible  RKE2 engine (this)    ──► ~/.kube/config
Layer 2  deploy/k8s                         add-ons (kube-vip LB, …)
```

## Layout

| Path | Purpose |
| --- | --- |
| `ansible.cfg` | inventory `inventory/hosts.ini`, roles `roles/`, host-key checking **on**, `become` via sudo |
| `inventory/hosts.ini` | `[rke2_server]` rke2-master-01 (10.20.0.10); `[rke2_agent]` rke2-worker-01/02 (10.20.0.21/.22); `[rke2_cluster:children]` |
| `inventory/group_vars/all.yml` | pinned `rke2_version`, `rke2_token` (from `$RKE2_TOKEN`), `cluster_name` |
| `site-rke2.yml` | preflight → `os_prep` (all) → `rke2_server` (serial 1) → `rke2_agent` |
| `roles/os_prep` | kernel modules, sysctls (Kubernetes + CIS), swap off, time sync |
| `roles/rke2_server` | `etcd` user, RKE2 server install, CIS profile, PSS, secrets encryption, node-token extraction, kubeconfig export |
| `roles/rke2_agent` | RKE2 agent install, join with the extracted node token |
| `health.yml` | read-only health report (safe to schedule) |
| `heal.yml` | human-approved remediation (dry run unless `heal_confirm=yes`) |

Group names use underscores because Ansible rejects `-` in group names; they
map to the requested node roles `rke2-server` / `rke2-agent`.

## Security controls

| Control | How | Verified by the play |
| --- | --- | --- |
| CIS Kubernetes Benchmark | `profile: "cis"` on server **and** agents; `etcd` system user; CIS sysctls (`vm.panic_on_oom=0`, `vm.overcommit_memory=1`, `kernel.panic=10`, `kernel.panic_on_oops=1`) so kubelet's `protect-kernel-defaults` passes | service starts and node is Ready (RKE2 refuses to start under `cis` when prerequisites are missing) |
| Pod Security Standards | `restricted` enforce/audit/warn cluster-wide via `/etc/rancher/rke2/rke2-pss.yaml`; only `kube-system`, `cis-operator-system`, `tigera-operator` exempt | kube-apiserver runs with `--admission-control-config-file` |
| Secrets encryption at rest | `secrets-encryption: true` (aescbc) | `rke2 secrets-encrypt status` shows `Encryption Status: Enabled` |
| Workload isolation from control plane | `CriticalAddonsOnly=true:NoExecute` taint on the server (`rke2_server_taint`) | — |
| Secret handling | cluster token only from `$RKE2_TOKEN`; configs `0600` root; every task touching a token is `no_log` | preflight asserts ≥ 32 chars |
| Agent join pinning | agents use the extracted node token (`K10<CA hash>::server:…`), which verifies the cluster CA before sending the secret | — |
| Supply chain | RKE2 version pinned; the install script checks the release tarball against its sha256 checksum file | — |

## Run it

Ansible needs a Linux control node. WSL 2 (Ubuntu) on the Hyper-V host works;
it reaches 10.20.0.0/24 through the host. From the repository root in WSL:

```bash
sudo apt-get install -y ansible-core
cd deploy/01-k8s-engine/rke2-ansible

# 1. Inventory: the committed hosts.ini matches Layer 0's defaults. If you
#    changed Layer 0's nodes/network, regenerate it (from PowerShell):
#    tofu -chdir=deploy/00-infra/private-hyperv output -raw ansible_inventory_ini | Set-Content -Encoding utf8 deploy/01-k8s-engine/rke2-ansible/inventory/hosts.ini

# 2. Trust the new host keys once (compare fingerprints with the VM console).
for ip in 10.20.0.10 10.20.0.21 10.20.0.22; do ssh-keyscan -H "$ip" >> ~/.ssh/known_hosts; done
ansible rke2_cluster -m ansible.builtin.ping

# 3. Cluster secret: generate ONCE, store in your vault, reuse for every run.
export RKE2_TOKEN="$(openssl rand -hex 32)"

# 4. Bootstrap.
ansible-playbook site-rke2.yml

# 5. Use it.
kubectl get nodes -o wide
```

Re-running `site-rke2.yml` is safe: every task is idempotent (packages
`state: present`, templated config, install only when the pinned version is
not already installed, services `state: started`); a restart happens only
when a config file actually changed.

### Kubeconfig

The first server's `/etc/rancher/rke2/rke2.yaml` is written to
`~/.kube/config` on the controller with the server URL rewritten from
`127.0.0.1` to `10.20.0.10` and the `default` cluster/user/context renamed to
`iotee-pc`. If a different `~/.kube/config` already exists it is kept as a
timestamped backup next to it. To write elsewhere:
`ansible-playbook site-rke2.yml -e rke2_kubeconfig_dest=~/.kube/iotee-pc.yaml`.

To use kubectl from the LAN through the host's port forward
(`prep-hyperv-host.ps1 -ApiServerForwardTo 10.20.0.10`), add the host's LAN
address to the certificate: `-e '{"rke2_tls_san": ["192.168.1.20"]}'` (your
host's address), then point the kubeconfig at it.

## Verify the hardening yourself

```bash
ssh iotee@10.20.0.10 sudo /usr/local/bin/rke2 secrets-encrypt status
kubectl run psa-probe --image=busybox --restart=Never --privileged -- sleep 1   # must be REJECTED (restricted PSS)
kubectl get node rke2-master-01 -o jsonpath='{.spec.taints}'
```

For a full CIS report, run kube-bench or the Rancher CIS operator from Layer 2
(it is a workload, so it does not belong in this layer).

## Operations

```bash
ansible-playbook health.yml                                                     # read-only; JSON in reports/
ansible-playbook heal.yml --limit rke2-worker-02 -e heal_action=restart         # dry run
ansible-playbook heal.yml --limit rke2-worker-02 -e heal_action=restart -e heal_confirm=yes
ansible-playbook heal.yml --limit rke2-worker-02 -e heal_action=drain   -e heal_confirm=yes
ansible-playbook heal.yml --limit rke2-worker-02 -e heal_action=replace         # prints the rebuild steps only
```

- **Add a worker:** add it to Layer 0's `nodes`, `tofu apply`, regenerate
  `hosts.ini`, then `ansible-playbook site-rke2.yml --limit rke2-master-01,rke2-worker-03`
  (the first server must be in the limit: it supplies the node token).
- **Upgrade:** change `rke2_version` in `inventory/group_vars/all.yml` in a
  PR; run `--limit rke2_server` first, then `--limit rke2-master-01,rke2_agent`.
  One minor version at a time.
- **Grow to HA:** 3 hosts in `[rke2_server]` (the preflight accepts 1, 3 or 5);
  extra servers join the first one on 9345.
- **Single-server recovery:** with one server, etcd lives only there. Losing
  its VM means restoring an etcd snapshot (RKE2 takes them every 12 h into
  `/var/lib/rancher/rke2/server/db/snapshots`) with
  `rke2 server --cluster-reset --cluster-reset-restore-path=<snapshot>` and the
  **same** `RKE2_TOKEN`. Copy snapshots off the VM; Layer 0 has no backups.

## Out of scope for this layer

Service meshes, Kafka, kube-vip / LoadBalancer, GitOps controllers, CIS
scanning operators, application workloads → Layer 2+ (`deploy/k8s`).

## Verification status

Checked offline while writing: YAML parse of every file, Jinja rendering of
all templates with the default inventory, `ansible-playbook --syntax-check`,
and that only `ansible.builtin` modules are used. **Not yet run against real
VMs**; record the first real run's result here.
