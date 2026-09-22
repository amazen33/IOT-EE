# Billing / monetization — M7

## Milestone
M7, per `CLAUDE.md`'s milestone list: "optional monetization." Optional
means "ships behind a default-off runtime flag," not "may never be
built" — this milestone is fully implemented and gated like every other.

## Bounded contexts touched
billing (new). Reads, but does not modify, `domain_core.devices`. No
other bounded context may import `billing` (see "Security constraints
observed").

## Verified inputs
Decisions supplied by the repository owner for this stage (see ADR 0008
for full rationale):
- Flag granularity: global default (off) + per-tenant override, one
  resolution point, `is_monetization_enabled(tenant_id)`.
- Disabled-mode contract: no billing side effects, all features remain
  available, no billing state is required to exist anywhere else.
- Usage dimension: certified (`ACTIVE`) device-count per tenant per
  period.
- Pricing/closure: effective-dated price plans, immutable period
  closure, reversal-only correction.
- Outage isolation: mechanical, via a new `scripts/check.py` import-scan
  rule, not just a documented convention.

Still open, per `docs/inputs.md`'s M7-M8 row and not addressed by this
stage: whether monetization is commercially required at all (this
milestone builds the capability without asserting the answer);
commercial/currency-conversion/tax requirements; a real payment
provider or invoicing system.

## What is implemented
- `billing/flags.py` — `MonetizationFlags`: global default, per-tenant
  overrides, `is_monetization_enabled(tenant_id)` as the sole resolution
  point every other module checks first.
- `billing/metering.py` — `UsageRecord` (validated period/count shape),
  `certified_device_count` (counts `ACTIVE` devices via
  `domain_core.devices.DeviceRegistry`), `meter_tenant_period` (returns
  `None`, touching nothing else, when disabled for the tenant).
- `billing/pricing.py` — `PricePlan` (frozen, validated: non-negative
  price, 3-letter currency, ISO-8601 `effective_from`), `PricePlanCatalog`
  (append-only per-`plan_id` version history; `plan_as_of` resolves the
  latest version not after a given date; a duplicate `effective_from` is
  rejected).
- `billing/closure.py` — `ClosedPeriod` (validated: `total_amount` must
  equal `price_per_device * certified_device_count` for an original
  closure, or negate that magnitude for a reversal), `PeriodLedger`
  (write-once per period_id, and rejects a second original closure for
  the same tenant/period range -- duplicate safety), `reverse_closed_period`
  (the only correction path: a new, linked entry, never an edit).
- `billing/service.py` — `close_tenant_period`, composing the above
  behind the flag check: returns `None` immediately (no metering read,
  no plan resolution, no ledger write) when monetization is disabled for
  the tenant.
- `fixtures/billing.synthetic.json` — a synthetic global default,
  per-tenant overrides, and two versions of one synthetic price plan.
- `scripts/check.py` — `_check_billing_isolation`: ast-scans every other
  domain package and fails the gate if any of them imports `billing`.

## What is explicitly blocked (not passed, not silently skipped)
- **Per-feature entitlement or tiered feature gating.** No
  `is_feature_entitled`-shaped function exists anywhere in this package,
  deliberately -- see ADR 0008, decision 2. Feature access and
  monetization state are separate axes; this milestone only implements
  the latter.
- **A real payment provider or invoicing system.** No payment is ever
  taken, charged, or invoiced; `ClosedPeriod` is an internal record, not
  a customer-facing invoice.
- **Tax, currency conversion, or other commercial/compliance handling.**
  `PricePlan.currency` is a plain 3-letter code with no conversion or
  tax logic applied to it.
- **Whether monetization is commercially required at all.** This
  milestone builds the capability, default off; it does not decide that
  any real tenant should be billed.
- **A billing-outage *notification* mechanism.** Isolation (this
  milestone) means billing failing can't break core operations; it does
  not include alerting anyone that billing failed.

These are documented here as blocked, per the contract's requirement to
record unavailable external verification as blocked rather than passed.

## Acceptance criteria (traced to `docs/test-plan.md`'s M7 row)
| Requirement | How it is met |
| --- | --- |
| Finalized usage only | `billing.metering.meter_tenant_period` only ever reads the current certified count; `ClosedPeriod` snapshots it at closure time and is never recomputed afterward |
| Duplicate safety | `PeriodLedger.close_period` rejects a second original closure for the same tenant/period range (`PeriodAlreadyClosedError`) and a repeated `period_id` (`DuplicatePeriodIdError`) |
| Effective-dated pricing | `tests/test_billing_pricing.py` — `PricePlanCatalog.plan_as_of` resolution across versions |
| Period closure/reversals | `tests/test_billing_closure.py` — immutable `ClosedPeriod`, reversal-only correction, net-amount-after-reversal |
| Billing outage isolation | `tests/test_billing_flag_matrix.py` (disabled-mode contract) + `scripts/check.py`'s `_check_billing_isolation` (mechanical import ban) |

## Security constraints observed
- No domain package other than `billing` itself may import it --
  mechanically enforced by `scripts/check.py`'s `_check_billing_isolation`,
  the same ast-based scan style as the existing no-forbidden-imports
  policy. A billing bug or outage cannot, by construction, reach
  telemetry ingestion, RBAC, firmware rollout, or evidence handling.
- `is_monetization_enabled` is the only flag-shaped function in this
  package; there is no parallel feature-entitlement check anywhere in
  `billing`, so a future per-feature entitlement system cannot
  accidentally inherit or be confused with this milestone's on/off
  switch.
- A closed period's `total_amount` is validated against
  `certified_device_count * price_per_device` at construction time --
  `ClosedPeriod` cannot represent an inconsistent snapshot even
  transiently.

## Intended files
`billing/{__init__,flags,metering,pricing,closure,service}.py`,
`fixtures/billing.synthetic.json`,
`tests/test_billing_{flags,metering,pricing,closure,service,flag_matrix,fixture}.py`,
`docs/adr/0008-m7-monetization.md`, this file, and the extension to
`scripts/check.py`.

## Relevant tests
All `tests/test_billing_*.py`, run by `python scripts/check.py`.

## Non-goals (explicitly out of scope for this change)
- Per-feature entitlement or tiered feature gating (see blocked list).
- A real payment provider, invoicing system, or outage-notification
  mechanism.
- Tax, currency conversion, or other commercial/compliance handling.
- Any billing data that introduces PII or payment-card scope.
- Deployment-profile concerns (M8).
- Any infrastructure provisioning.
