# Deployment Studio / multi-environment — M8

## Milestone
M8, per `CLAUDE.md`'s milestone list: "Deployment Studio/multi-environment."

## Bounded contexts touched
deployment_studio (new). Reads, but does not modify, `domain_core.rbac`
(two new permissions added to the catalog). No other bounded context may
be imported by `deployment_studio` (see "Security constraints observed").

## Verified inputs
Decisions supplied by the repository owner for this stage (see ADR 0009
for full rationale):
- Plan/validate only -- no real apply, no infrastructure provisioned.
- Deployment profiles: immutable, append-only, versioned registry with
  default-deny lookup; closed environment/provider/region enums; prod
  requires approval; RBAC-gated approval (`deployment.approve_profile`);
  tenant-isolated.
- Least-privilege runner + approval audit: explicit RBAC-gated plan
  lifecycle (`deployment.approve_plan`), immutable audit trail, and a
  runner-policy contract structurally forbidden from declaring
  apply/destroy.
- GitOps reconciliation: pure desired-vs-observed comparison, no real
  controller.

Still open, per `docs/inputs.md`'s M8 row: on-prem/cloud capacity/network
constraints; approved IaC runner/GitOps ownership and budgets. Both
remain explicitly unaddressed by this stage's code.

## What is implemented
- `deployment_studio/profiles.py` — `Environment`/`Provider`/`Region`/`Tier`
  enums (region validated against its provider's allowed set),
  `DeploymentProfile` (prod requires full approval metadata at
  construction), `ApprovedProfileRegistry` (append-only per
  `(tenant_id, profile_id)`; `profile_as_of` is default-deny --
  `UnknownProfileError`, never `None`, for an unregistered or
  not-yet-effective profile; a duplicate `effective_from` is rejected).
- `deployment_studio/approval.py` — `approve_profile`, gated on the new
  `deployment.approve_profile` permission.
- `deployment_studio/audit.py` — `PlanAuditEntry`/`PlanAuditLog`:
  write-once, append-only audit trail for plan lifecycle transitions. A
  self-contained shape, not an import of `evidence.records` (isolation).
- `deployment_studio/plan.py` — `DeploymentPlan` and its
  draft -> validated -> (approved | rejected) lifecycle:
  `create_plan`/`validate_plan` (default-deny profile lookup + IaC
  document validation)/`approve_plan`/`reject_plan` (both gated on the
  new `deployment.approve_plan` permission, distinct from
  `deployment.approve_profile`). Every transition writes an audit entry.
- `deployment_studio/iac.py` — `validate_iac_plan_document`: structural
  validation only (required sections, at least one well-formed
  resource), and a hard refusal of any document declaring `apply: true`.
- `deployment_studio/runner.py` — `RunnerPolicy`: declarative
  least-privilege permission-scope contract; `allowed_actions` may never
  contain `"apply"` or `"destroy"` at this milestone.
- `deployment_studio/gitops.py` — `reconcile_desired_state`: pure
  comparison of a plan's `desired_state` against a supplied
  `observed_state`, reporting field-level drift.
- `fixtures/deployment_profiles.synthetic.json` — two synthetic
  profiles (an unapproved dev profile, an approved prod profile),
  exercised end-to-end in `tests/test_deployment_studio_fixture.py`.
- `scripts/check.py` — `_check_deployment_studio_isolation`
  (`deployment_studio` may import none of `billing`, `firmware`,
  `evidence`, `gateway`), built on a shared
  `_check_forbidden_package_imports` helper that `_check_billing_isolation`
  now also uses; `deployment_studio` added to
  `_CORE_PACKAGES_FORBIDDEN_FROM_IMPORTING_BILLING`.

## What is explicitly blocked (not passed, not silently skipped)
- **Any real `terraform`/`ansible` apply, or cloud provider API call.**
  `deployment_studio.iac` only validates document shape; nothing invokes
  a real IaC engine.
- **Real secret storage, provider API validation, or live infrastructure
  reads.** No credential, cloud API call, or live resource state exists
  anywhere in this package.
- **A real GitOps controller (ArgoCD/Flux) connection or live cluster
  read.** `reconcile_desired_state` only compares two caller-supplied
  dicts.
- **A real least-privilege IAM binding or runner credential.**
  `RunnerPolicy` is a declarative record, not an actual permission grant.
- **Approved capacity/network constraints, and IaC runner/GitOps
  ownership.** Recorded as open inputs (`docs/inputs.md`); this
  milestone's code does not decide or enforce either.

These are documented here as blocked, per the contract's requirement to
record unavailable external verification as blocked rather than passed.

## Acceptance criteria (traced to `docs/test-plan.md`'s M8 row)
| Requirement | How it is met |
| --- | --- |
| Plan policy | `tests/test_deployment_studio_iac.py` — structural IaC document validation, hard refusal of `apply: true` |
| Least-privilege runner | `tests/test_deployment_studio_runner.py` — `RunnerPolicy` structurally forbids apply/destroy |
| Approval audit | `tests/test_deployment_studio_plan.py` + `tests/test_deployment_studio_audit.py` — every lifecycle transition is a required, write-once audit entry |
| IaC validation/plan | `deployment_studio.iac.validate_iac_plan_document`, invoked from `validate_plan` |
| GitOps reconciliation | `tests/test_deployment_studio_gitops.py` — desired-vs-observed drift detection |
| Isolated environment deployment and rollback across approved profiles | Modeled at the plan/policy level: a plan validates only against an effective, registered profile (`tests/test_deployment_studio_profiles.py`'s default-deny tests); real applied-state rollback stays blocked (see blocked list) |

## Security constraints observed
- `deployment_studio` imports none of `billing`, `firmware`, `evidence`,
  or `gateway` -- mechanically enforced by `_check_deployment_studio_isolation`.
  It is also on the list of packages `billing` itself must never import,
  so the two packages cannot reach each other in either direction.
- A `PROD`-environment profile cannot be constructed without full
  approval metadata already set -- `ProfileApprovalRequiredError` is
  raised at construction, not caught later by a review step.
- `ApprovedProfileRegistry.profile_as_of` is default-deny: an
  unregistered or not-yet-effective profile is an error, never a `None`
  a caller might mistake for "use a default."
- `approve_plan`/`reject_plan` require `deployment.approve_plan`;
  `approve_profile` requires the distinct `deployment.approve_profile`
  -- a principal authorized to approve one is not automatically
  authorized for the other.

## Intended files
`deployment_studio/{__init__,profiles,approval,audit,plan,iac,runner,gitops}.py`,
`fixtures/deployment_profiles.synthetic.json`,
`tests/test_deployment_studio_{profiles,approval,audit,plan,iac,runner,gitops,fixture}.py`,
`docs/adr/0009-m8-deployment-studio.md`, this file, the two-permission
addition to `domain_core/rbac.py`'s `PERMISSION_CATALOG`, and the
extension to `scripts/check.py`.

## Relevant tests
All `tests/test_deployment_studio_*.py`, run by `python scripts/check.py`.

## Non-goals (explicitly out of scope for this change)
- Any real IaC execution, cloud/on-prem provisioning, or GitOps
  controller connection (see blocked list).
- Approved capacity/network constraints and IaC runner/GitOps ownership
  as organizational decisions.
- Monetization concerns (M7, already shipped and isolated from this
  package).
- Any infrastructure provisioning.
