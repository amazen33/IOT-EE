import unittest

from domain_core.tenancy import (
    IsolationMode,
    Tenant,
    TenantSizeTier,
    choose_isolation_mode,
    derive_schema_name,
)


class TenancyTests(unittest.TestCase):
    def test_enterprise_tier_always_schema_per_tenant(self):
        self.assertEqual(
            choose_isolation_mode(TenantSizeTier.ENTERPRISE, provisioned_device_count=0),
            IsolationMode.SCHEMA_PER_TENANT,
        )

    def test_standard_tier_below_threshold_is_shared_rls(self):
        self.assertEqual(
            choose_isolation_mode(TenantSizeTier.STANDARD, provisioned_device_count=10),
            IsolationMode.SHARED_RLS,
        )

    def test_standard_tier_above_threshold_promotes_to_schema_per_tenant(self):
        self.assertEqual(
            choose_isolation_mode(TenantSizeTier.STANDARD, provisioned_device_count=5000),
            IsolationMode.SCHEMA_PER_TENANT,
        )

    def test_negative_device_count_rejected(self):
        with self.assertRaises(ValueError):
            choose_isolation_mode(TenantSizeTier.STANDARD, provisioned_device_count=-1)

    def test_derive_schema_name_is_deterministic_and_safe(self):
        name = derive_schema_name("synthetic-tenant-a")
        self.assertEqual(name, "tenant_synthetic_tenant_a")
        self.assertEqual(name, derive_schema_name("synthetic-tenant-a"))

    def test_tenant_schema_name_only_set_for_schema_per_tenant(self):
        small = Tenant(tenant_id="synthetic-small", name="Small Co", tier=TenantSizeTier.STANDARD)
        self.assertEqual(small.isolation_mode, IsolationMode.SHARED_RLS)
        self.assertIsNone(small.schema_name)

        large = Tenant(tenant_id="synthetic-large", name="Large Co", tier=TenantSizeTier.ENTERPRISE)
        self.assertEqual(large.isolation_mode, IsolationMode.SCHEMA_PER_TENANT)
        self.assertEqual(large.schema_name, "tenant_synthetic_large")

    def test_invalid_tenant_fields_rejected(self):
        with self.assertRaises(ValueError):
            Tenant(tenant_id="has space", name="X", tier=TenantSizeTier.STANDARD)
        with self.assertRaises(ValueError):
            Tenant(tenant_id="ok-id", name="", tier=TenantSizeTier.STANDARD)
        with self.assertRaises(ValueError):
            Tenant(tenant_id="ok-id", name="X", tier=TenantSizeTier.STANDARD, provisioned_device_count=-5)


if __name__ == "__main__":
    unittest.main()
