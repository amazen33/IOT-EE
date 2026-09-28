# Layer 2 kube-vip lab installation — 2026-09-28 (Africa/Cairo)

Scope: the single-host Hyper-V lab at `10.20.0.0/24`, with three Ready RKE2
`v1.35.7+rke2r1` nodes. This is lab evidence only, not HA or production
validation. The installed manifests are the render of the package merged in
PR #34 (merge commit `ebc272c`).

Before apply, `tests/verify-layer2.py` passed from the repository and from
a temporary copy outside it. GitHub CI passed the offline package test,
Kustomize render, and kubeconform schema check. A Kubernetes server-side dry
run accepted all nine new objects; `kubectl get svc -A` showed no existing
LoadBalancer Service. The API warned that the vendored cloud-controller
manifest still mentions the deprecated `node-role.kubernetes.io/master`
affinity key; the object was accepted.

After the reviewed apply, the cloud-controller Deployment rolled out 1/1
and the kube-vip DaemonSet rolled out 3/3. A temporary restricted-Pod-Security
smoke namespace, echo Deployment, and LoadBalancer Service were applied from
`tests/smoke.yaml`. Its pod was Ready on `rke2-worker-01`; kube-vip assigned
`10.20.0.40` from the configured pool. An HTTP request from the Hyper-V host
to `http://10.20.0.40:8080/` returned status 200 and body `layer2-ok`.
The smoke manifest was then deleted with `--wait=true`; a follow-up namespace
query returned no resource. The cloud-controller Deployment remained 1/1 and the
DaemonSet 3/3 after cleanup.

`health.yml` was not rerun as part of this immediate Layer 2 preflight;
node readiness and the target API were checked directly. The entire
`.40-.49` pool was not probed for external address use before apply. The
successful `.40` assignment and HTTP response do not prove every pool
address is conflict-free. The running cluster still has RKE2's bundled
ingress-nginx; it predates the fresh-install default that disables that
chart in PR #33. This smoke test used port 8080 and did not migrate ingress.
Review the deprecated upstream affinity label at the next component upgrade.

Not established: a fresh Layer 1 health report for this apply, full-pool
conflict detection, failover under node loss, sustained throughput, pool
exhaustion behavior, LAN ingress through Windows NAT, upgrades/rollback,
storage or secrets readiness, host redundancy, and production support.
The lab still has one physical host and one control plane.
