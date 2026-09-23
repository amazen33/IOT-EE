import unittest

from billing.flags import MonetizationFlags
from billing.metering import InvalidPeriodError, UsageRecord, certified_device_count, meter_tenant_period
from domain_core.devices import Device, DeviceId, DeviceRegistry, DeviceStatus


def _registry_with(tenant_id, statuses):
    registry = DeviceRegistry()
    for status in statuses:
        registry.register(
            Device(device_id=DeviceId.generate(tenant_id), tank_id="synthetic-tank-001", label="Tank sensor", status=status)
        )
    return registry


class UsageRecordValidationTests(unittest.TestCase):
    def test_valid_record_constructs(self):
        record = UsageRecord(
            tenant_id="synthetic-tenant-a", period_start="2026-01-01T00:00:00Z",
            period_end="2026-02-01T00:00:00Z", certified_device_count=3,
        )
        self.assertEqual(record.certified_device_count, 3)

    def test_rejects_period_end_before_period_start(self):
        with self.assertRaises(InvalidPeriodError):
            UsageRecord(
                tenant_id="synthetic-tenant-a", period_start="2026-02-01T00:00:00Z",
                period_end="2026-01-01T00:00:00Z", certified_device_count=3,
            )

    def test_rejects_negative_count(self):
        with self.assertRaises(InvalidPeriodError):
            UsageRecord(
                tenant_id="synthetic-tenant-a", period_start="2026-01-01T00:00:00Z",
                period_end="2026-02-01T00:00:00Z", certified_device_count=-1,
            )


class CertifiedDeviceCountTests(unittest.TestCase):
    def test_counts_only_active_devices(self):
        registry = _registry_with(
            "synthetic-tenant-a",
            [DeviceStatus.ACTIVE, DeviceStatus.ACTIVE, DeviceStatus.REGISTERED, DeviceStatus.SUSPENDED, DeviceStatus.DECOMMISSIONED],
        )
        self.assertEqual(certified_device_count(registry, "synthetic-tenant-a"), 2)

    def test_zero_when_no_active_devices(self):
        registry = _registry_with("synthetic-tenant-a", [DeviceStatus.REGISTERED, DeviceStatus.SUSPENDED])
        self.assertEqual(certified_device_count(registry, "synthetic-tenant-a"), 0)

    def test_tenants_are_isolated(self):
        registry = DeviceRegistry()
        registry.register(Device(device_id=DeviceId.generate("synthetic-tenant-a"), tank_id="synthetic-tank-001", label="A", status=DeviceStatus.ACTIVE))
        registry.register(Device(device_id=DeviceId.generate("synthetic-tenant-b"), tank_id="synthetic-tank-002", label="B", status=DeviceStatus.ACTIVE))
        self.assertEqual(certified_device_count(registry, "synthetic-tenant-a"), 1)
        self.assertEqual(certified_device_count(registry, "synthetic-tenant-b"), 1)


class MeterTenantPeriodTests(unittest.TestCase):
    def test_returns_none_when_monetization_disabled(self):
        flags = MonetizationFlags(global_default=False)
        registry = _registry_with("synthetic-tenant-a", [DeviceStatus.ACTIVE, DeviceStatus.ACTIVE])
        result = meter_tenant_period(
            flags=flags, registry=registry, tenant_id="synthetic-tenant-a",
            period_start="2026-01-01T00:00:00Z", period_end="2026-02-01T00:00:00Z",
        )
        self.assertIsNone(result)

    def test_returns_usage_record_when_enabled(self):
        flags = MonetizationFlags(global_default=True)
        registry = _registry_with("synthetic-tenant-a", [DeviceStatus.ACTIVE, DeviceStatus.ACTIVE, DeviceStatus.SUSPENDED])
        result = meter_tenant_period(
            flags=flags, registry=registry, tenant_id="synthetic-tenant-a",
            period_start="2026-01-01T00:00:00Z", period_end="2026-02-01T00:00:00Z",
        )
        self.assertIsNotNone(result)
        self.assertEqual(result.certified_device_count, 2)


if __name__ == "__main__":
    unittest.main()
