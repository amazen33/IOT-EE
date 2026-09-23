"""billing.service: composes flags + metering + pricing + closure behind
the single ``is_monetization_enabled`` resolution point.

Decision (docs/adr/0008-m7-monetization.md): this is the only place in
the package that ties the other modules together, modeling how a real
caller (a scheduled billing job, in a real deployment) would use them.
``close_tenant_period`` returns ``None`` -- without metering usage,
resolving a price plan, or touching the ledger -- when monetization is
disabled for the tenant, satisfying the disabled-mode contract: no
billing side effect occurs at all, not even a metering read.

Duplicate safety: closing the same tenant/period twice is rejected by
``billing.closure.PeriodLedger`` itself (``PeriodAlreadyClosedError``);
this module does not add a second layer of deduplication, since a single
source of truth for "has this period been closed" is simpler to reason
about than two.
"""

from __future__ import annotations

from billing.closure import ClosedPeriod, PeriodLedger
from billing.flags import MonetizationFlags
from billing.metering import meter_tenant_period
from billing.pricing import PricePlanCatalog
from domain_core.devices import DeviceRegistry


def close_tenant_period(
    *,
    flags: MonetizationFlags,
    registry: DeviceRegistry,
    pricing_catalog: PricePlanCatalog,
    ledger: PeriodLedger,
    tenant_id: str,
    plan_id: str,
    period_id: str,
    period_start: str,
    period_end: str,
    closed_at: str,
) -> ClosedPeriod | None:
    usage = meter_tenant_period(
        flags=flags, registry=registry, tenant_id=tenant_id, period_start=period_start, period_end=period_end,
    )
    if usage is None:
        return None  # monetization disabled for this tenant: no side effect at all
    plan = pricing_catalog.plan_as_of(plan_id, period_end)
    total_amount = round(plan.price_per_device * usage.certified_device_count, 2)
    closed = ClosedPeriod(
        period_id=period_id,
        tenant_id=tenant_id,
        period_start=period_start,
        period_end=period_end,
        certified_device_count=usage.certified_device_count,
        price_per_device=plan.price_per_device,
        currency=plan.currency,
        total_amount=total_amount,
        closed_at=closed_at,
    )
    ledger.close_period(closed)
    return closed
