import unittest

from billing.flags import InvalidTenantIdError, MonetizationFlags


class MonetizationFlagsTests(unittest.TestCase):
    def test_defaults_to_global_default_false(self):
        flags = MonetizationFlags()
        self.assertFalse(flags.is_monetization_enabled("synthetic-tenant-a"))

    def test_global_default_true_applies_to_unlisted_tenants(self):
        flags = MonetizationFlags(global_default=True)
        self.assertTrue(flags.is_monetization_enabled("synthetic-tenant-a"))

    def test_tenant_override_wins_over_global_default_false(self):
        flags = MonetizationFlags(global_default=False)
        flags.set_tenant_override("synthetic-tenant-a", True)
        self.assertTrue(flags.is_monetization_enabled("synthetic-tenant-a"))
        self.assertFalse(flags.is_monetization_enabled("synthetic-tenant-b"))

    def test_tenant_override_wins_over_global_default_true(self):
        flags = MonetizationFlags(global_default=True)
        flags.set_tenant_override("synthetic-tenant-a", False)
        self.assertFalse(flags.is_monetization_enabled("synthetic-tenant-a"))
        self.assertTrue(flags.is_monetization_enabled("synthetic-tenant-b"))

    def test_clear_tenant_override_falls_back_to_global_default(self):
        flags = MonetizationFlags(global_default=False)
        flags.set_tenant_override("synthetic-tenant-a", True)
        flags.clear_tenant_override("synthetic-tenant-a")
        self.assertFalse(flags.is_monetization_enabled("synthetic-tenant-a"))

    def test_has_tenant_override_reports_presence(self):
        flags = MonetizationFlags()
        self.assertFalse(flags.has_tenant_override("synthetic-tenant-a"))
        flags.set_tenant_override("synthetic-tenant-a", True)
        self.assertTrue(flags.has_tenant_override("synthetic-tenant-a"))

    def test_set_global_default_changes_resolution_for_unlisted_tenants(self):
        flags = MonetizationFlags(global_default=False)
        flags.set_global_default(True)
        self.assertTrue(flags.is_monetization_enabled("synthetic-tenant-a"))

    def test_rejects_invalid_tenant_id(self):
        flags = MonetizationFlags()
        with self.assertRaises(InvalidTenantIdError):
            flags.is_monetization_enabled("bad id")
        with self.assertRaises(InvalidTenantIdError):
            flags.set_tenant_override("bad id", True)


if __name__ == "__main__":
    unittest.main()
