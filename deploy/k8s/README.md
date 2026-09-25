# `deploy/k8s`

Layer 2: cluster add-ons applied after the Layer 1 engine
(`deploy/01-k8s-engine/rke2-ansible`) is up. Layer 1 installs only RKE2;
everything that runs *on* the cluster starts here.

| Path | What it does |
| --- | --- |
| `kube-vip/` | LoadBalancer Services: kube-vip-cloud-provider, the address pool (10.20.0.40-.49), and a kube-vip DaemonSet in services-only mode that announces the addresses. No control-plane VIP: clients reach the API on the server's address. |

The cluster enforces the **restricted** Pod Security Standard in every
namespace except the RKE2 system namespaces (Layer 1). Anything added here
that needs more (host networking, capabilities) must run in `kube-system` or
in a namespace labelled explicitly, with the reason in its PR.

## Scaling: why there is no Cluster Autoscaler

The Kubernetes Cluster Autoscaler can only add nodes through a cloud or
Cluster API provider, and none exists for Hyper-V. Rather than ship an
autoscaler that cannot create a VM, node count is declared in Git:

1. Add (or remove) an entry in the `nodes` map of
   `deploy/00-infra/private-hyperv/terraform.tfvars`, in a PR.
2. After review, `tofu apply` creates (or deletes) the VM; re-export the
   inventory from its `ansible_inventory_ini` output.
3. From `deploy/01-k8s-engine/rke2-ansible`:
   `ansible-playbook site-rke2.yml --limit rke2-master-01,rke2-worker-03`
   joins it (the first server is included because it supplies the join token).

Removing a node: drain it first (`heal.yml -e heal_action=drain` in Layer 1),
then remove its entry. Every apply stays a human-approved step, per the
development contract's rule that infrastructure changes need explicit
approval.

Pod-level autoscaling (HorizontalPodAutoscaler) is unaffected: it needs only
metrics-server, which RKE2 installs by default.
