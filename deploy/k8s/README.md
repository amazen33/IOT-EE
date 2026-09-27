# Layer 2: kube-vip LoadBalancer add-on

This directory is a self-contained, **lab-specific** Layer 2 package. Copy
the whole directory into another checkout to render and test it; Kustomize
reads only local files. It consumes a working Kubernetes API and does not
read Layer 0 state, Layer 1 inventory, or IOT-EE application configuration.

The package installs the v0.0.12 kube-vip cloud controller, a v1.2.4
kube-vip DaemonSet in services-only ARP mode, RBAC, and a ConfigMap assigning
LoadBalancer Services addresses from `10.20.0.40-10.20.0.49`. The pool and
`eth0` interface are specific to the current Hyper-V lab; inspect them
before using this package elsewhere. It does **not** create a control-plane
VIP, install storage, secrets, Kafka, APISIX, observability, or any user
application. The cloud-controller manifest is vendored from the pinned
upstream release; its source and SHA-256 are in `kube-vip/UPSTREAM.md`.

## Test this package alone

From any working directory, with Python 3.12+ and PyYAML 6.0.3:

```bash
python /path/to/k8s/tests/verify-layer2.py
```

This checks that all Kustomize resources are local, the pinned upstream
manifest is byte-for-byte intact, resource identities are unique, the
images and services-only mode are reviewed, and the lab address pool is
valid. CI additionally runs `kubectl kustomize` and `kubeconform -strict` on
the render. No test contacts a cluster or applies resources.

## Lab preflight and reviewed install

The current lab has three Ready RKE2 nodes and `eth0`, but this package has
**not** been installed. Before an apply, verify that the nodes, API, pool,
and existing LoadBalancer controllers still match the review:

```bash
kubectl get nodes -o wide
kubectl get pods -A --field-selector=status.phase!=Running
kubectl -n kube-system get deploy kube-vip-cloud-provider --ignore-not-found
kubectl -n kube-system get ds kube-vip-ds --ignore-not-found
kubectl get svc -A -o wide
kubectl kustomize /path/to/k8s > /tmp/layer2-reviewed.yaml
kubectl diff -f /tmp/layer2-reviewed.yaml
kubectl apply --dry-run=server -f /tmp/layer2-reviewed.yaml
```

Review the rendered file and diff, then apply it only through the separately
approved infrastructure gate. After applying, wait for the cloud-controller
Deployment and kube-vip DaemonSet to become available, create a temporary
restricted-Pod-Security echo Service of type `LoadBalancer`, and prove it
receives a pool address that answers from the Hyper-V host. Record the
address, response, component logs, and cleanup. The lab's Windows NAT does
not make that address reachable from the LAN without a separately reviewed
NAT mapping and firewall rule.

Rollback is a reviewed `kubectl delete -f` of the same rendered manifest,
after checking whether any Services still depend on the assigned addresses.
Removing the controller while such Services are in use causes traffic loss.

The cluster has one physical host and one control plane. A successful
LoadBalancer smoke test would prove this lab package works, not production
high availability or disaster recovery. Storage and secrets are separate
Layer 2 packages still to be designed and tested.
