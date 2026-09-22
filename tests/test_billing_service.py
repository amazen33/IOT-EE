import unittest

from billing.closure import PeriodAlreadyClosedError, PeriodLedger
from billing.flags import MonetizationFlags
from billing.pricing import PricePlan, PricePlanCatalog
from billing.service import close_tenant_period
from domain_core.devices import Device, DeviceId, DeviceRegistry, DeviceStatus


def _setup(tenant_id="synthetic-tenant-a", active_count=4, global_default=True):
    flags = MonetizationFlags(global_default=global_default)
    registry = DeviceRegistry()
    for _ in range(active_count):
        registry.register(Device(device_id=DeviceId.generate(tenant_id), tank_id="synthetic-tank-001", label="Sensor", status=DeviceStatus.ACTIVE))
    catalog = PricePlanCatalog()
    catalog.register_version(PricePlan(plan_id="synthetic-plan-standard", price_per_device=2.5, currency="USD", effective_from="2026-01-01T00:00:00Z"))
    ledger = PeriodLedger()
    return flags, registry, catalog, ledger


class CloseTenantPeriodTests(unittest.TestCase):
    def test_closes_a_period_and_computes_total(self):
        flags, registry, catalog, ledger = _setup(active_count=4)
        closed = close_tenant_period(
            flags=flags, registry=registry, pricing_catalog=catalog, ledger=ledger,
            tenant_id="synthetic-tenant-a", plan_id="synthetic-plan-standard", period_id="synthetic-period-001",
            period_start="2026-01-01T00:00:00Z", period_end="2026-02-01T00:00:00Z", closed_at="2026-02-02T00:00:00Z",
        )
        self.assertEqual(closed.certified_device_count, 4)
        self.assertEqual(closed.total_amount, 10.0)
        self.assertEqual(ledger.get("synthetic-period-001").total_amount, 10.0)

    def test_returns_none_and_touches_no_ledger_when_disabled(self):
        flags, registry, catalog, ledger = _setup(active_count=4, global_default=False)
        result = close_tenant_period(
            flags=flags, registry=registry, pricing_catalog=catalog, ledger=ledger,
            tenant_id="synthetic-tenant-a", plan_id="synthetic-plan-standard", period_id="synthetic-period-001",
            period_start="2026-01-01T00:00:00Z", period_end="2026-02-01T00:00:00Z", closed_at="2026-02-02T00:00:00Z",
        )
        self.assertIsNone(result)
        with self.assertRaises(KeyError):
            ledger.get("synthetic-period-001")

    def test_closing_the_same_tenant_period_twice_is_rejected(self):
        flags, registry, catalog, ledger = _setup(active_count=4)
        close_tenant_period(
            flags=flags, registry=registry, pricing_catalog=catalog, ledger=ledger,
            tenant_id="synthetic-tenant-a", plan_id="synthetic-plan-standard", period_id="synthetic-period-001",
            period_start="2026-01-01T00:00:00Z", period_end="2026-02-01T00:00:00Z", closed_at="2026-02-02T00:00:00Z",
        )
        with self.assertRaises(PeriodAlreadyClosedError):
            close_tenant_period(
                flags=flags, registry=registry, pricing_catalog=catalog, ledger=ledger,
                tenant_id="synthetic-tenant-a", plan_id="synthetic-plan-standard", period_id="synthetic-period-002",
                period_start="2026-01-01T00:00:00Z", period_end="2026-02-01T00:00:00Z", closed_at="2026-02-03T00:00:00Z",
            )

    def test_uses_the_price_plan_version_in_effect_at_period_end(self):
        flags, registry, catalog, ledger = _setup(active_count=2)
        catalog.register_version(PricePlan(plan_id="synthetic-plan-standard", price_per_device=5.0, currency="USD", effective_from="2026-06-01T00:00:00Z"))
        closed = close_tenant_period(
            flags=flags, registry=registry, pricing_catalog=catalog, ledger=ledger,
            tenant_id="synthetic-tenant-a", plan_id="synthetic-plan-standard", period_id="synthetic-period-late",
            period_start="2026-07-01T00:00:00Z", period_end="2026-08-01T00:00:00Z", closed_at="2026-08-02T00:00:00Z",
        )
        self.assertEqual(closed.price_per_device, 5.0)
        self.assertEqual(closed.total_amount, 10.0)


if __name__ == "__main__":
    unittest.main()
