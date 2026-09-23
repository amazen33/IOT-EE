import unittest

from reporting.timezone import UnknownTimezoneError, to_tenant_local


class ToTenantLocalTests(unittest.TestCase):
    def test_converts_utc_to_positive_offset_zone(self):
        result = to_tenant_local("2026-01-01T00:00:00Z", "Africa/Cairo")
        self.assertEqual(result, "2026-01-01T02:00:00+02:00")

    def test_converts_utc_to_negative_offset_zone(self):
        result = to_tenant_local("2026-01-01T12:00:00Z", "America/New_York")
        self.assertEqual(result, "2026-01-01T07:00:00-05:00")

    def test_utc_zone_is_a_no_op_offset(self):
        result = to_tenant_local("2026-01-01T00:00:00Z", "UTC")
        self.assertEqual(result, "2026-01-01T00:00:00+00:00")

    def test_unknown_timezone_raises(self):
        with self.assertRaises(UnknownTimezoneError):
            to_tenant_local("2026-01-01T00:00:00Z", "Not/A_Real_Zone")

    def test_invalid_timestamp_raises(self):
        with self.assertRaises(ValueError):
            to_tenant_local("not-a-timestamp", "UTC")

    def test_storage_string_is_never_mutated(self):
        source = "2026-01-01T00:00:00Z"
        to_tenant_local(source, "Africa/Cairo")
        self.assertEqual(source, "2026-01-01T00:00:00Z")


if __name__ == "__main__":
    unittest.main()
