# `deploy/k8s`

Cluster add-ons applied after the Ansible bootstrap (see `deploy/README.md`,
step 4).

| Path | What it does |
| --- | --- |
| `kube-vip/` | kube-vip-cloud-provider plus the LoadBalancer address pool. The control-plane VIP itself is not here: it is a static pod Ansible writes on each server, so it exists before the API does. |

## Scaling: why there is no Cluster Autoscaler

The Kubernetes Cluster Autoscaler can only add nodes through a cloud or
Cluster API provider, and none exists for Hyper-V. Rather than ship an
autoscaler that cannot create a VM, node count is declared in Git:

1. Add (or remove) an entry in the `nodes` map of
   `deploy/00-infra/private-hyperv/terraform.tfvars`, in a PR.
2. After review, `tofu apply` creates (or deletes) the VM; re-export the
   inventory from its `ansible_inventory` output.
3. `ansible-playbook playbooks/bootstrap.yml --limit rke2-worker-03` (the new
   node's name) joins it.

Removing a node: drain it first (`playbooks/heal.yml -e heal_action=drain`),
then remove its entry. Every apply stays a human-approved step, per the
development contract's rule that infrastructure changes need explicit
approval.

Pod-level autoscaling (HorizontalPodAutoscaler) is unaffected: it needs only
metrics-server, which RKE2 installs by default.
