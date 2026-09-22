"""billing bounded context: optional monetization, per CLAUDE.md's
milestone list ("M7 optional monetization") and the repository owner's
scoping for this stage (docs/adr/0008-m7-monetization.md).

"Optional" means: monetization ships behind a runtime feature flag,
default OFF, with a per-tenant override; it is a buildable milestone, not
a "maybe never built" one. The system must function identically whether
or not monetization is enabled -- disabling it stops metering/pricing/
closure side effects, and nothing else. This module never gates feature
*access*: there is no ``is_feature_entitled`` function anywhere in this
package, deliberately -- per-feature entitlement is a distinct, deferred
concern (see docs/billing.md's non-goals), and conflating it with the
monetization on/off switch was explicitly ruled out during scoping.

Modules:
- ``billing.flags``: the single resolution point, ``MonetizationFlags``
  (global default + per-tenant override), that every other module in this
  package is gated behind.
- ``billing.metering``: certified device-count usage per tenant per
  period, reusing M2's ``domain_core.devices`` as the source of truth.
- ``billing.pricing``: effective-dated price plans, versioned and never
  mutated once effective.
- ``billing.closure``: immutable period closure (``ClosedPeriod``) and
  reversal-only correction (never editing a closed period in place).
- ``billing.service``: composes the above behind the flag resolution
  point -- the only place ``close_tenant_period`` / metering are called
  from in this package's own tests, modeling how a real caller would use
  it.

Isolation: no module in ``domain_core``, ``migration_studio``,
``ingestion``, ``gateway``, ``reporting``, ``firmware``, or ``evidence``
may import this package -- enforced mechanically by
``scripts/check.py``'s ``_check_billing_isolation``. Billing can only be
reached through the single resolution point; a billing failure can never
break core telemetry, RBAC, firmware, or evidence operations, by
construction. This package itself performs no network, process, or
filesystem I/O outside the repository (same
``_check_no_forbidden_imports`` policy as every other domain package):
no real payment processor, invoicing system, or tax/compliance engine
exists here -- see docs/billing.md for what is blocked.
"""
