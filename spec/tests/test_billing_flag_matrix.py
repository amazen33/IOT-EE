"""Required test matrix (per the repository owner's M7 scoping): every
combination of global default and per-tenant override, exercised
end-to-end through billing.service.close_tenant_period.

| State                          | Expected                                    |
| ------------------------------ | -------------------------------------------- |
| Enabled (global)                | Metering/pricing/closure active             |
| Disabled (global)                | No billing side effects; ledger untouched   |
| Enabled per-tenant override      | Only the overriding tenant is billed        |
| Disabled per-tenant override     | Override tenant exempt; others follow global |
"""

import unittest

from billing.closure import PeriodLedger
from billing.flags import MonetizationFlags
from billing.pricing import PricePlan, PricePlanCatalog
from billing.service import close_tenant_period
from domain_core.devices import Device, DeviceId, DeviceRegistry, DeviceStatus

_PERIOD_START = "2026-01-01T00:00:00Z"
_PERIOD_END = "2026-02-01T00:00:00Z"
_CLOSED_AT = "2026-02-02T00:00:00Z"


def _registry_with_active_devices(tenant_id, count):
    registry = DeviceRegistry()
    for _ in range(count):
        registry.register(Device(device_id=DeviceId.generate(tenant_id), tank_id="synthetic-tank-001", label="Sensor", status=DeviceStatus.ACTIVE))
    return registry


def _catalog():
    catalog = PricePlanCatalog()
    catalog.register_version(PricePlan(plan_id="synthetic-plan-standard", price_per_device=2.5, currency="USD", effective_from="2026-01-01T00:00:00Z"))
    return catalog


def _close(*, flags, tenant_id, period_id, registry=None):
    return close_tenant_period(
        flags=flags,
        registry=registry or _registry_with_active_devices(tenant_id, 4),
        pricing_catalog=_catalog(),
        ledger=PeriodLedger(),
        tenant_id=tenant_id,
        plan_id="synthetic-plan-standard",
        period_id=period_id,
        period_start=_PERIOD_START,
        period_end=_PERIOD_END,
        closed_at=_CLOSED_AT,
    )


class GlobalEnabledTests(unittest.TestCase):
    def test_metering_pricing_and_closure_are_active(self):
        flags = MonetizationFlags(global_default=True)
        closed = _close(flags=flags, tenant_id="synthetic-tenant-a", period_id="synthetic-period-ge1")
        self.assertIsNotNone(closed)
        self.assertEqual(closed.total_amount, 10.0)


class GlobalDisabledTests(unittest.TestCase):
    def test_no_billing_side_effects_and_ledger_is_untouched(self):
        flags = MonetizationFlags(global_default=False)
        ledger = PeriodLedger()
        result = close_tenant_period(
            flags=flags, registry=_registry_with_active_devices("synthetic-tenant-a", 4), pricing_catalog=_catalog(),
            ledger=ledger, tenant_id="synthetic-tenant-a", plan_id="synthetic-plan-standard",
            period_id="synthetic-period-gd1", period_start=_PERIOD_START, period_end=_PERIOD_END, closed_at=_CLOSED_AT,
        )
        self.assertIsNone(result)
        with self.assertRaises(KeyError):
            ledger.get("synthetic-period-gd1")


class TenantOverrideEnabledTests(unittest.TestCase):
    def test_only_the_overriding_tenant_is_billed(self):
        flags = MonetizationFlags(global_default=False)
        flags.set_tenant_override("synthetic-tenant-billed", True)

        billed = _close(flags=flags, tenant_id="synthetic-tenant-billed", period_id="synthetic-period-toe1")
        self.assertIsNotNone(billed)

        not_billed = _close(flags=flags, tenant_id="synthetic-tenant-other", period_id="synthetic-period-toe2")
        self.assertIsNone(not_billed)


class TenantOverrideDisabledTests(unittest.TestCase):
    def test_override_tenant_is_exempt_while_others_follow_global(self):
        flags = MonetizationFlags(global_default=True)
        flags.set_tenant_override("synthetic-tenant-exempt", False)

        exempt = _close(flags=flags, tenant_id="synthetic-tenant-exempt", period_id="synthetic-period-tod1")
        self.assertIsNone(exempt)

        other = _close(flags=flags, tenant_id="synthetic-tenant-other", period_id="synthetic-period-tod2")
        self.assertIsNotNone(other)


if __name__ == "__main__":
    unittest.main()
