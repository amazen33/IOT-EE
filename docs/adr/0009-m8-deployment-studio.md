# ADR 0009 — M8: Deployment Studio, multi-environment, plan/validate-only

Status: accepted for planning, 2026-09-22. Implementation is milestone-gated,
same discipline as ADR 0001-0008.

## Context

M8 per `CLAUDE.md`'s milestone list is "Deployment Studio/multi-environment."
`docs/test-plan.md`'s M8 row scopes the expected gate: "Plan policy,
least-privilege runner, approval audit, IaC validation/plan, GitOps
reconciliation, isolated environment deployment and rollback across
approved profiles." `docs/inputs.md`'s M8 row's open inputs (selected
deployment profiles, on-prem/cloud capacity/network constraints,
approved IaC runner/GitOps ownership and budgets) are addressed by this
milestone only to the extent scoped below. The repository owner supplied
the scoping decisions below.

## Decisions

1. **Plan/validate only -- no real apply, no infrastructure
   provisioned.** Consistent with `CLAUDE.md`'s "no unrestricted cloud
   commands or cloud admin credentials in browser flows" and every prior
   milestone's real-backend deferral. Nothing in `deployment_studio`
   executes an IaC apply, calls a cloud provider API, reads live
   infrastructure state, or provisions anything.
2. **Deployment profiles: an immutable, append-only, versioned registry
   with default-deny lookup.** `deployment_studio.profiles.DeploymentProfile`
   is a validated record (`profile_id`, `tenant_id`, `environment` ∈
   `{dev, staging, prod}`, `provider`/`region` from closed enums with a
   provider-region compatibility check, `tier`, declarative
   `capabilities`, optional RPO/RTO, approval metadata, `effective_from`).
   `ApprovedProfileRegistry` never mutates a registered profile --
   correcting one means registering a new version at a new
   `effective_from` -- and `profile_as_of` raises `UnknownProfileError`
   for an unregistered or not-yet-effective profile rather than
   returning `None`. A `PROD` profile cannot be registered without
   `approved=True` plus `approved_by`/`approved_at` already set.
   Approval is RBAC-gated via a new `deployment.approve_profile`
   permission (added to `domain_core.rbac.PERMISSION_CATALOG`), mirroring
   M6's `evidence.legal_hold` single-gated-function pattern.
   `capabilities` are declarative labels only; `deployment_studio` itself
   performs no I/O and reaches no real provider API to validate them.
3. **Least-privilege runner + approval audit: explicit RBAC-gated
   lifecycle transitions, immutable audit trail, structural runner
   contract.** `deployment_studio.plan.DeploymentPlan` moves
   draft -> validated -> (approved | rejected) through explicit functions,
   never a bare boolean; `approve_plan`/`reject_plan` require a second,
   distinct permission, `deployment.approve_plan` (approving a reusable
   profile and approving one specific plan against it are different
   authorities). Every transition is recorded to
   `deployment_studio.audit.PlanAuditLog`, a self-contained, write-once
   audit shape -- structurally similar to M6's evidence records but
   deliberately not an import of `evidence.records` (see decision 5).
   `deployment_studio.runner.RunnerPolicy` records the least-privilege
   permission scopes a real runner would need, and is structurally
   forbidden from ever declaring `"apply"` or `"destroy"` in
   `allowed_actions` at this milestone.
4. **GitOps reconciliation: a pure desired-vs-observed comparison.**
   `deployment_studio.gitops.reconcile_desired_state` compares a plan's
   declared `desired_state` against a caller-supplied `observed_state`
   snapshot and reports field-level drift -- same shape as M3's
   `migration_studio.shadow_parity` and M6's `evidence.dr`. No real
   GitOps controller (ArgoCD/Flux) connection or live cluster read
   exists; `observed_state` is always supplied by the caller.
5. **Isolation: `deployment_studio` imports no `billing`, `firmware`,
   `evidence`, or `gateway`.** It is a leaf, inert package with respect to
   the other bounded contexts it could plausibly touch (deployment
   status could tempt an import of firmware rollout state, or billing's
   deployment-profile tier; both are refused). Enforced mechanically by
   a new `scripts/check.py` check, `_check_deployment_studio_isolation`,
   built on the same reusable `_check_forbidden_package_imports` helper
   `_check_billing_isolation` now also uses. `deployment_studio` is also
   added to `_CORE_PACKAGES_FORBIDDEN_FROM_IMPORTING_BILLING`, so billing
   can never reach into it either. It does import `domain_core.rbac`
   (for `Principal`/permissions), the same direction every RBAC-gated
   module in this codebase already depends in.

## Consequences

- "Isolated environment deployment and rollback across approved
  profiles" (per `docs/test-plan.md`'s M8 row) is modeled at the plan/
  policy level this milestone: a plan can only be validated against an
  effective, registered profile (isolation by profile), and rollback in
  the applied sense stays blocked pending real infrastructure --
  `deployment_studio.gitops` gives the drift-detection half of "did the
  applied state match," not a real rollback executor.
- A `DeploymentPlan`'s lifecycle is fully auditable: every transition
  (create/validate/approve/reject) is a required, write-once log entry,
  so "who approved what, and when" is answerable from the audit log
  alone, without inferring it from plan state.
- No claim of real IaC execution, real cloud/on-prem provisioning, a
  real GitOps controller, or a real least-privilege IAM binding is made
  anywhere in this milestone.

## Non-goals (blocked pending real infrastructure/decisions — see `docs/deployment-studio.md`)

- Any real `terraform`/`ansible` apply, or any cloud provider API call.
- Real secret storage, provider API validation, or live infrastructure
  reads.
- A real GitOps controller (ArgoCD/Flux) connection or live cluster
  state read.
- A real least-privilege IAM binding or runner credential.
- Approved capacity/network constraints and IaC runner/GitOps ownership
  as organizational decisions (recorded as open inputs, not resolved by
  this milestone's code).
- Any infrastructure provisioning (still prohibited at this stage per
  `CLAUDE.md`).
