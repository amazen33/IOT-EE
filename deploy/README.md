# Deployment layers and current lab evidence

IOT-EE currently has two independently testable installation packages and a
Kustomize add-on tree. Each package reads its own settings and accepts a
documented handover from its predecessor; no package reads another layer's
working directory or Terraform state.

| Step | Directory | Owns | Independent check | Status |
| --- | --- | --- | --- | --- |
| Layer 0 | `00-infra/private-hyperv/` | Hyper-V host preparation, VMs, disks, network and inventory output | `powershell -NoProfile -ExecutionPolicy Bypass -File tests/verify.ps1`; optional `-RunTofu` | Package merged; its current revision has not been applied to the lab |
| Layer 1 | `01-k8s-engine/rke2-ansible/` | Ubuntu preparation, RKE2, Canal, dedicated RKE2 data mount and health | `bash tests/verify-layer1.sh` | Package merged; its new storage path has not been applied to the lab |
| Layer 2 | `k8s/` | kube-vip LoadBalancer add-on | `python k8s/tests/verify-layer2.py`, `kubectl kustomize k8s` and kubeconform in CI | Installed in the lab; `10.20.0.40` smoke test passed on 2026-09-28 |
| Layer 3 | Not implemented | LGTM and OpenTelemetry Collector | Synthetic logs, metrics, traces, service graph and recovery tests required | Planned |
| Platform services | Not implemented | Kafka and APISIX, each in its own replaceable package | Per-package install, health, security, persistence/routing and rollback tests required | Planned |

Layer 0 uses the pinned `windsorcli/hyperv` provider locally on the Windows
host. Copy its whole directory to use it in another project. Its
`scripts/deploy-layer0.ps1` produces git-ignored `out/ansible-inventory.ini`,
`out/nodes.json` and `out/network.json`; it does not write into Layer 1.
Layer 1 accepts an INI inventory from any compatible VM provider, with its
real `inventory/hosts.ini` and site overrides git-ignored. Follow each
package's README for prerequisites, review gates and installation commands.

## Lab status and limits

On 2026-09-27, the owner and read-only checks confirmed three running
Hyper-V VMs and RKE2 `v1.35.7+rke2r1`: one control-plane node and two
workers were Ready, API `/readyz` was healthy, and secrets encryption was
enabled. This is evidence of the **earlier lab installation**, not a live
test of the newly merged standalone packages. The running cluster uses Canal
and RKE2's bundled ingress-nginx. Its 20 GiB secondary disks are unmounted;
RKE2 data is on each OS disk. The new Layer 1 storage policy requires an
explicit disk of at least 40 GiB and has not been run on these nodes.

The lab is one physical Hyper-V host behind Windows NAT with one control
plane. It has neither host nor control-plane high availability. Layer 2
kube-vip is installed and smoke-tested. Storage classes, a secrets backend,
Kafka, APISIX, and the observability stack have no confirmed lab installation.
None of the current
checks establishes production capacity, HA, disaster recovery or 24/7
operation.

## Installation sequence and package boundaries

1. **Layer 0:** run its own offline test; review a saved OpenTofu plan and
   host capacity; install only after the infrastructure approval gate. Export
   its inventory as a handover file.
2. **Layer 1:** put the handover inventory in its ignored `inventory/hosts.ini`,
   set and verify each node's `rke2_data_device`, run its own offline test and
   inventory validation, verify SSH host keys at the VM console, then perform
   the separately approved Ansible run. Canal is the default. RKE2 does not
   support changing a running cluster's primary CNI in place.
3. **Layer 2 prerequisites:** install and test the chosen LoadBalancer,
   storage and secrets components as separate packages. `k8s/` currently
   contains only kube-vip manifests; it does not install storage or a vault.
4. **Layer 3 observability:** install the Collector and LGTM stack before
   application instrumentation. Its future package must prove synthetic
   ingest/query, a service-graph edge, access controls, retention, restart,
   failure isolation and rollback without IOT-EE application code.
5. **Platform services:** install Kafka and APISIX in separate version-pinned
   packages. Kafka must prove durable produce/consume, broker recovery and
   authorization. APISIX must prove HTTPS routing, JWT enforcement, rate
   limiting, WebSocket/gRPC handling and rollback. Instrument both against
   the installed observability stack. Do not mark either installed based on
   a catalog entry or an ADR alone.
6. **Applications:** release services only after the required platform
   dependencies have passed their own readiness gates.

HAProxy is an optional external load-balancer package for a topology that
needs it, such as a future multi-server control plane. It is not part of the
single-host lab. The current lab runs RKE2's bundled ingress-nginx, but
fresh Layer 1 installations disable it so ingress can be supplied by a
separately installed, replaceable gateway package. If APISIX and NGINX
coexist, document their distinct traffic roles and test the handoff.
Removing ingress-nginx from the running lab requires a separately reviewed
migration; the Layer 1 guard prevents a routine rerun from doing it.

CI runs static and offline checks only; it never applies infrastructure.
The Layer 0 `-RunTofu` option additionally runs OpenTofu in a temporary copy.
On the owner's Windows session, Application Control blocked `tofu.exe`, while
the 33 default PowerShell checks passed and GitHub CI validated OpenTofu.
That local restriction is not evidence of a failed or successful live apply.
