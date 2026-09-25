# ADR XXXX (proposed) -- Private-cloud infrastructure: RKE2 on Hyper-V, kube-vip, Git-declared scaling

Status: proposed. Unnumbered draft per `docs/adr/README.md`; the number is
assigned at merge. Records the decisions the `deploy/` skeleton encodes. It is
the "edge-to-cloud deployment" gap named in `docs/track-c-execution-plan.md`,
for the private-cloud profile only.

Related: ADR 0011 (platform architecture), ADR 0013 (microservice autonomy),
ADR 0015 (observability authority), ADR 0019 (streaming and RAG: Flink never
on the K3s edge).

## Context

The private cloud runs on two Windows machines with Hyper-V: a wired Dell
OptiPlex desktop and a Lenovo E15 laptop on Wi-Fi. The platform brief places
K3s on test/demo/edge and RKE2/OpenShift on the private cloud, and asks for
IaC-driven, vendor-neutral deployment. CLAUDE.md reserves `deploy/` for
Ansible, Terraform/OpenTofu, Helm and Argo CD, and its development contract
forbids infrastructure, deploy or destructive actions without explicit human
approval.

## Decision

1. **Distribution: RKE2** for the private cloud (K3s stays the edge/demo
   distribution). HA control plane is RKE2 servers with embedded etcd, in
   odd counts (1, 3 or 5).
2. **Hypervisor automation: the community `taliesins/hyperv` Terraform
   provider** over WinRM/HTTPS, one provider alias per host. Nodes are Gen-2
   VMs on differencing disks over an immutable, dated Ubuntu golden image,
   configured by a cloud-init NoCloud seed ISO. VM checkpoints are disabled.
3. **Layering:** Terraform owns VMs only and generates the Ansible
   inventory. Ansible owns OS state and RKE2. `deploy/k8s` owns cluster
   add-ons (later managed by Argo CD).
4. **Control-plane VIP and LoadBalancer services: kube-vip** in ARP mode,
   as an RKE2 static pod on every server, with kube-vip-cloud-provider
   assigning Service addresses from a declared pool.
5. **Scaling is declared in Git, not autoscaled.** Nodes are a keyed map in
   Terraform variables; adding or removing an entry is a reviewed change
   followed by a human-run apply. The Kubernetes Cluster Autoscaler is not
   used: it has no Hyper-V (or Cluster API Hyper-V) provider.
6. **Healing is tiered by risk.** systemd restarts RKE2 automatically.
   Restart, drain and uncordon are playbook actions that require explicit
   confirmation. Node replacement is a documented manual procedure, never
   automated.
7. **Secrets never enter Git or Terraform state:** Hyper-V credentials via
   `TF_VAR_*`, the RKE2 join token via the operator's environment and vault.
8. **Versions are pinned** (RKE2 stable channel, kube-vip, the cloud-provider
   manifest, the provider) and upgraded deliberately.

## Consequences

- **HA scope is VM/process failure, not host failure:** with two physical
  hosts no etcd layout survives losing one host. Host-level HA needs a third
  host.
- RKE2 servers must run on the wired host; kube-vip ARP is unreliable
  behind Hyper-V's Wi-Fi bridging.
- The IaC is outside the Maven build and gets its own static CI gate
  (`.github/workflows/infra.yml`). Nothing in CI can reach the hosts.
- Adding capacity takes minutes of human action rather than seconds of
  automation; that is the accepted trade for approval-gated infrastructure.
- A community provider carries maintenance risk; the module boundary
  (`modules/hyperv-node`) keeps a switch to another hypervisor provider local.

## Open items (not decided here)

- Remote Terraform state with locking, once more than one operator applies.
- Argo CD installation and the `deploy/argocd`, `deploy/helm` layout.
- Public-cloud (EKS/GKE/AKS) and OpenShift profiles.
- Where observability (ADR 0015's LGTM stack) runs in this cluster.
- A third physical host for host-level HA.

## Alternatives rejected

- **Cluster Autoscaler with a custom external gRPC provider for Hyper-V:**
  a bespoke component that creates VMs without human approval; conflicts
  with the development contract and adds a critical path we would own.
- **K3s on the private cloud:** the brief assigns K3s to edge/demo.
- **VM checkpoints for rollback:** rewinding an etcd member corrupts the
  cluster; nodes are rebuilt instead.
- **`count`-based node list:** removing one node renumbers and rebuilds the
  others.
