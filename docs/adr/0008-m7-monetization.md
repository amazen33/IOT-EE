# ADR 0008 — M7: optional monetization behind a runtime feature flag

Status: accepted for planning, 2026-09-22. Implementation is milestone-gated,
same discipline as ADR 0001-0007.

## Context

M7 per `CLAUDE.md`'s milestone list is "optional monetization." The
repository owner clarified during scoping what "optional" means here:
M7 is a buildable milestone, not one that may simply never be built.
Monetization ships behind a runtime system feature flag, default off,
and the system must function identically whether or not it is enabled --
mandatory billing would break on-prem, air-gapped, internal, and
enterprise "billing handled externally" deployments. `docs/inputs.md`'s
M7-M8 row's open inputs (whether monetization is required, certified
usage dimensions, closure rules, commercial/currency/tax requirements)
are addressed by this milestone only to the extent scoped below; the
rest remain open, recorded in docs/billing.md.

## Decisions

1. **Flag granularity and resolution: global default + per-tenant
   override, one resolution point.** `billing.flags.MonetizationFlags`
   holds a global default (off) and per-tenant overrides; resolution is
   override-if-set else global default, all through one method,
   `is_monetization_enabled(tenant_id)`. Every billing-side-effecting
   function in this package is required to check this first. This
   mirrors the capability/permission-gate pattern already used elsewhere
   (`evidence.legal_hold`), generalized to a boolean flag rather than an
   RBAC permission, since the caller here is a scheduled process, not
   necessarily an authenticated principal.
2. **Disabled-mode contract: no side effects, unrestricted features, no
   required billing state.** When monetization is disabled for a tenant,
   `billing.metering.meter_tenant_period` and
   `billing.service.close_tenant_period` return `None` immediately --
   no usage is read, no plan is resolved, no ledger entry is written.
   All currently-shipped features (reports, firmware rollout, evidence,
   etc.) remain fully available regardless of this flag: monetization
   state and feature access are two separate axes, and this milestone
   deliberately does not introduce any `is_feature_entitled`-shaped
   function. Per-feature entitlement is a distinct, explicitly deferred
   concern (see docs/billing.md's non-goals).
3. **Usage dimension: certified device-count.** `billing.metering`
   reuses M2's `domain_core.devices.DeviceRegistry` as the sole source of
   truth -- an `ACTIVE` device is certified for the period; `REGISTERED`,
   `SUSPENDED`, and `DECOMMISSIONED` devices are not. This avoids a
   second, event-volume-based counting mechanism that would need
   reconciling against M3's outbox.
4. **Pricing and closure: effective-dated plans, immutable closure,
   reversal-only correction.** `billing.pricing.PricePlan` is a frozen,
   versioned plan with an `effective_from` date; `PricePlanCatalog`
   resolves "the price in effect as of a date" and rejects registering a
   second version at an already-used effective date -- correcting a
   price is always a new version, never an edit. `billing.closure`
   snapshots a period's finalized usage and price into an immutable
   `ClosedPeriod`; `PeriodLedger` is write-once per (tenant, period) for
   the original closure and rejects closing the same period twice
   (duplicate safety); the only correction path is
   `reverse_closed_period`, which adds a new, linked entry negating the
   original amount and never edits or removes it.
5. **Billing-outage isolation: mechanical, not just documented.**
   `scripts/check.py` gains `_check_billing_isolation`, which
   ast-scans `domain_core`, `migration_studio`, `ingestion`, `gateway`,
   `reporting`, `firmware`, and `evidence` and fails the gate if any of
   them imports `billing`. Billing can only be reached through the
   single resolution point described in decision 1; a billing failure
   structurally cannot break core telemetry, RBAC, firmware, or evidence
   operations.

## Consequences

- The required test matrix (global enabled/disabled x per-tenant
  override enabled/disabled) is exercised end-to-end in
  `tests/test_billing_flag_matrix.py`: enabling monetization globally
  bills every tenant unless overridden off; disabling it globally bills
  no tenant and leaves the ledger untouched unless overridden on; a
  per-tenant override always wins over the global default in either
  direction.
- `close_tenant_period` returning `None` for a disabled tenant is
  observably different from it returning a zero-amount `ClosedPeriod` --
  the former means "no billing activity occurred at all," the latter
  would mean "billing ran and found zero certified devices." This
  distinction is what makes the disabled-mode contract ("no billing side
  effects") testable rather than just documented.
- No claim of real payment processing, invoicing, tax/compliance
  handling, or per-feature entitlement is made anywhere in this
  milestone.

## Non-goals (blocked pending real infrastructure/decisions — see `docs/billing.md`)

- Per-feature entitlement or tiered feature gating driven by billing
  state (deliberately kept out of this package entirely).
- A real payment provider, invoicing system, or billing-outage
  notification mechanism.
- Tax, currency conversion, or other commercial/compliance handling.
- Any billing data that introduces PII or payment-card scope.
- Any infrastructure provisioning (still prohibited at this stage per
  `CLAUDE.md`).

