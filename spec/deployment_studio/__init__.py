"""deployment_studio bounded context: multi-environment deployment
profiles, plan lifecycle/approval, a least-privilege runner-policy
contract, and GitOps drift detection (M8, per CLAUDE.md's milestone
list: "Deployment Studio/multi-environment").

Decision (docs/adr/0009-m8-deployment-studio.md): this milestone is
plan/validate-only, consistent with every prior milestone's real-backend
deferral and with CLAUDE.md's "no unrestricted cloud commands or cloud
admin credentials in browser flows." Nothing in this package executes an
IaC apply, calls a cloud provider API, reads live infrastructure state,
or provisions anything.

Modules:
- ``deployment_studio.profiles``: ``DeploymentProfile`` (versioned,
  effective-dated, environment/provider/region from closed enums) and
  ``ApprovedProfileRegistry`` (append-only, default-deny lookup -- an
  unregistered or not-yet-effective profile raises rather than
  returning ``None``).
- ``deployment_studio.plan``: ``DeploymentPlan`` and its
  draft -> validated -> approved (or rejected) lifecycle, each transition
  RBAC-gated and recorded to an audit log.
- ``deployment_studio.audit``: an append-only, write-once audit log for
  plan lifecycle transitions -- a self-contained shape, not an import of
  ``evidence.records`` (see isolation, below).
- ``deployment_studio.iac``: structural validation of an IaC plan
  document (required sections, at least one resource, a hard refusal of
  any document declaring ``apply: true``). No terraform/ansible binding.
- ``deployment_studio.runner``: ``RunnerPolicy``, a declarative
  least-privilege permission-scope contract for what a real runner would
  eventually be allowed to do -- structurally forbidden from ever
  declaring an "apply" or "destroy" action at this milestone.
- ``deployment_studio.gitops``: a pure comparison function reporting
  drift between a plan's declared desired state and a caller-supplied
  observed-state snapshot. No real GitOps controller or live cluster
  read exists here.

Isolation: this package imports no ``billing``, ``firmware``,
``evidence``, or ``gateway`` module -- enforced mechanically by
``scripts/check.py``'s ``_check_deployment_studio_isolation`` -- and, like
every other domain package, performs no network, process, or filesystem
I/O outside the repository (``_check_no_forbidden_imports``). It is also
one of the packages billing itself must never be reachable from (see
``_check_billing_isolation``'s package list).
"""
