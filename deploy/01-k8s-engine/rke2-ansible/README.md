# Layer 1 — `deploy/01-k8s-engine/rke2-ansible`

Ansible that turns Ubuntu VMs from any compatible infrastructure layer into a
**CIS-profile RKE2 cluster**: one server (control plane + etcd) and two agents
(workers) in the lab profile. This layer owns OS preparation, the dedicated
RKE2 data mount, the primary CNI choice, and the raw Kubernetes engine
**only**. Cluster add-ons are Layer 2 (`deploy/02-cluster-addons`),
observability Layer 3, applications Layer 4; none of them belongs here.

```
Layer 0  any compatible VM provider        VMs, disks, static IPs  ──► inventory/hosts.ini (git-ignored)
Layer 1  deploy/01-k8s-engine/rke2-ansible  RKE2 engine (this)      ──► ~/.kube/config
Layer 2  deploy/02-cluster-addons           add-ons (kube-vip LB, …)
```

## Layout

| Path | Purpose | In Git? |
| --- | --- | --- |
| `ansible.cfg` | inventory path `inventory/hosts.ini`, roles `roles/`, host-key checking **on**, `become` via sudo. **No node addresses.** | yes |
| `inventory/hosts.example.ini` | Example inventory with RFC 5737 documentation addresses (`192.0.2.x`), so it can never reach a real machine | yes |
| `inventory/hosts.ini` | The real inventory, generated from Layer 0's `ansible_inventory_ini` output or copied from the example | **no** (git-ignored) |
| `inventory/group_vars/all/main.yml` | Reviewed defaults: pinned `rke2_version`, `rke2_token` (from `$RKE2_TOKEN`), `cluster_name` | yes |
| `inventory/group_vars/all/local.yml`, `inventory/host_vars/` | Site-specific, non-secret overrides (start from `inventory/local-overrides.example.yml`) | **no** (git-ignored) |
| `validate-inventory.yml`, `tasks/validate-inventory.yml` | Inventory checks run before any host is contacted | yes |
| `tests/inventories/bad-*.ini` | Negative fixtures CI proves are rejected | yes |
| `site-rke2.yml` | inventory validation → preflight → `os_prep` (all) → `rke2_server` (serial 1) → `rke2_agent` | yes |
| `guard-cni.yml`, `tasks/guard-cni.yml` | read-only check that refuses a CNI switch on an existing server | yes |
| `roles/os_prep/tasks/data_disk.yml` | validates and mounts an explicit disk before RKE2 installation; asks before formatting a blank disk | yes |
| `roles/os_prep` | kernel modules, sysctls (Kubernetes + CIS), swap off, time sync | yes |
| `roles/rke2_server` | Pod Security exemption guard, `etcd` user, RKE2 server install, CIS profile, PSS, secrets encryption, node-token extraction, kubeconfig export | yes |
| `roles/rke2_agent` | RKE2 agent install, join with the extracted node token | yes |
| `health.yml` | read-only health report (safe to schedule) | yes |
| `heal.yml` | human-approved remediation: `--limit`, one action tag, typed confirmation | yes |

Group names use underscores because Ansible rejects `-` in group names; they
map to the node roles `rke2-server` / `rke2-agent`.

## Inventory rules

- Node addresses and groups live **only** in the inventory,
  `inventory/group_vars/` and `inventory/host_vars/` -- never in
  `ansible.cfg`. No dynamic inventory plugin is approved yet; Layer 0's
  generated INI is the approved source.
- The real inventory, private addresses and local overrides stay out of
  Git (see `.gitignore`); CI fails if `inventory/hosts.ini` is committed.
- **Validate before every run:** `validate-inventory.yml` checks 1, 3 or 5
  servers, at least one agent, no host in both groups, DNS-label names,
  valid and unique IPv4 addresses and a non-root user, without contacting
  any host. `site-rke2.yml` runs the same checks first and also refuses the
  example's documentation addresses.
- **No `-e` for privileged switches or site overrides.** Extra vars have the
  highest precedence in Ansible and silently override every reviewed
  default. Put site values in `inventory/group_vars/all/local.yml`. The Pod
  Security exemption list is asserted against the reviewed value, so an
  override fails the run.
- Set `rke2_data_device` for every node in ignored site inventory after
  checking `lsblk`. The example `/dev/sdb` is not assumed by the play. It
  requires at least 40 GiB, refuses the root disk or an existing RKE2 data
  directory, and asks you to type the host and device before formatting a
  blank disk. `/var/lib/rancher` is mounted by UUID before RKE2 starts;
  systemd requires that mount for the RKE2 service.
- Canal is the default primary CNI, matching RKE2's default and the current
  lab. It uses Flannel for inter-node networking and Calico for network
  policies. Cilium is an explicit option for a **fresh** cluster after its
  requirements and policy behavior are tested. Run `guard-cni.yml` before a
  repeat deployment: RKE2 does not support changing a running cluster's
  primary CNI. Bundled ingress-nginx remains enabled by default to match the
  current lab; disabling it requires a fresh-cluster profile or a separate
  reviewed ingress migration.

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
| Dedicated storage | explicit device, minimum size, no existing data/signatures, typed first-format confirmation, UUID mount and systemd dependency | `/var/lib/rancher` is a separate mount before RKE2 starts |
| CNI drift | `rke2_cni: canal` is the default; existing CNI chart is checked before changes | a Canal-to-Cilium rerun fails before OS or RKE2 changes |

## Run it

Ansible needs a Linux control node. WSL 2 (Ubuntu) on the Hyper-V host works;
it reaches the lab VM network through the host. From this directory in WSL:

```bash
sudo apt-get install -y ansible-core
cd /path/to/rke2-ansible
export ANSIBLE_CONFIG="$PWD/ansible.cfg"  # needed on WSL /mnt/d mounts

# 1. Put the real inventory in inventory/hosts.ini (git-ignored). Export it
#    from a compatible infrastructure layer, or copy and edit the example.
#    Copy inventory/local-overrides.example.yml to
#    inventory/group_vars/all/local.yml and set rke2_data_device after lsblk.

# 2. Validate it (contacts no host).
ansible-playbook -i inventory/hosts.ini validate-inventory.yml

# 3. Record each node's SSH host key only after comparing its fingerprint
#    with the fingerprint displayed through the VM console. Then test SSH.
ansible -i inventory/hosts.ini rke2_cluster -m ansible.builtin.ping

# 4. Cluster secret: generate ONCE, store in your vault, reuse for every run.
export RKE2_TOKEN="$(openssl rand -hex 32)"

# 5. Bootstrap (re-validates the inventory first).
#    Each blank data disk asks for a typed format confirmation.
ansible-playbook -i inventory/hosts.ini site-rke2.yml

# 6. Use it.
kubectl get nodes -o wide
```

Re-running `site-rke2.yml` converges an unchanged cluster without reformatting
its mounted data disk. It refuses an existing cluster with a different primary
CNI, a missing data mount, or an unexpected disk. A CNI change or moving
existing RKE2 data requires a separate reviewed rebuild or migration plan.

### Kubeconfig and site overrides

The first server's `/etc/rancher/rke2/rke2.yaml` is written to
`~/.kube/config` on the controller with the server URL rewritten from
`127.0.0.1` to the server's address and the `default` cluster/user/context
renamed to `iotee-pc`. An existing different `~/.kube/config` is kept as a
timestamped backup.

To write it elsewhere, or to add the Hyper-V host's LAN name to the API
certificate (when using `prep-hyperv-host.ps1 -ApiServerForwardTo`), copy
`inventory/local-overrides.example.yml` to
`inventory/group_vars/all/local.yml` and set `rke2_kubeconfig_dest` or
`rke2_tls_san` there -- not with `-e`.

## Verify the hardening yourself

```bash
ansible -i inventory/hosts.ini rke2_server -b -m ansible.builtin.command -a '/usr/local/bin/rke2 secrets-encrypt status'
kubectl run psa-probe --image=busybox --restart=Never --privileged -- sleep 1   # must be REJECTED (restricted PSS)
kubectl get node rke2-master-01 -o jsonpath='{.spec.taints}'
```

For a full CIS report, run kube-bench or the Rancher CIS operator from Layer 2
(it is a workload, so it does not belong in this layer).

## Operations

```bash
ansible-playbook -i inventory/hosts.ini health.yml                                          # read-only on nodes; writes local JSON in reports/
ansible-playbook -i inventory/hosts.ini guard-cni.yml                                       # read-only; refuses an in-place CNI change
ansible-playbook -i inventory/hosts.ini heal.yml --limit rke2-worker-02 --tags restart      # asks you to type the node name
ansible-playbook -i inventory/hosts.ini heal.yml --limit rke2-worker-02 --tags drain
ansible-playbook -i inventory/hosts.ini heal.yml --limit rke2-worker-02 --tags uncordon
ansible-playbook -i inventory/hosts.ini heal.yml --limit rke2-worker-02 --tags replace      # prints the rebuild steps only
```

`heal.yml` refuses to run without `--limit` and exactly one action tag, and
changes nothing unless you type the node's name at the prompt (a
non-interactive run aborts). The action is a tag rather than an `-e` extra
var on purpose.

- **Add a worker:** add it to Layer 0's `nodes`, `tofu apply`, regenerate
  `inventory/hosts.ini`, validate it, then
  `ansible-playbook -i inventory/hosts.ini site-rke2.yml --limit rke2-master-01,rke2-worker-03`
  (the first server must be in the limit: it supplies the node token).
- **Upgrade:** change `rke2_version` in `inventory/group_vars/all/main.yml`
  and its entry in `deploy/catalog/tools.yaml` in one PR; run
  `--limit rke2_server` first, then `--limit rke2-master-01,rke2_agent`.
  One minor version at a time.
- **Grow to HA:** 3 hosts in `[rke2_server]` (validation accepts 1, 3 or 5);
  extra servers join the first one on 9345. A multi-host layout is a
  separate, planned profile.
- **Single-server recovery:** with one server, etcd lives only there. Losing
  its VM means restoring an etcd snapshot (RKE2 takes them every 12 h into
  `/var/lib/rancher/rke2/server/db/snapshots`) with
  `rke2 server --cluster-reset --cluster-reset-restore-path=<snapshot>` and the
  **same** `RKE2_TOKEN`. Copy snapshots off the VM; Layer 0 has no backups.

## Out of scope for this layer

Service meshes, Kafka, kube-vip / LoadBalancer, storage, GitOps
controllers, CIS scanning operators → Layer 2 (`deploy/02-cluster-addons`);
LGTM/OTel → Layer 3; application workloads → Layer 4.

## Verification status

Earlier static checks: `ansible-playbook --syntax-check` for every playbook against the
example inventory, the `ansible.builtin`-only rule, the example inventory
accepted and five negative fixtures rejected by `validate-inventory.yml`
(all in CI); earlier offline Jinja rendering of every template. The heal
gating (no tag, two tags, no limit, unconfirmed restart) and the Pod
Security exemption guard were exercised locally without contacting any
host. The owner reported a live run on 2026-09-27: RKE2
`v1.35.7+rke2r1`, 3/3 nodes Ready, API ready, secrets encryption enabled;
`health.yml` subsequently passed with no unhealthy nodes. That live cluster
uses **Canal** and has unmounted 20 GiB data disks. The dedicated data-disk
path in this branch has **not** been applied live; its 40 GiB minimum is a
deployment policy, not a CNI requirement. Rebuild or migrate the lab from a
reviewed plan before calling that storage configuration verified. Cilium
remains an optional fresh-cluster profile and has not been tested here.
