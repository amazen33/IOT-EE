import json
from pathlib import Path
import unittest

from billing.flags import MonetizationFlags
from billing.pricing import PricePlan, PricePlanCatalog

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "billing.synthetic.json"


class BillingFixtureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(FIXTURE_PATH.read_text())

    def test_tenant_override_ids_are_synthetic(self):
        for entry in self.fixture["tenant_overrides"]:
            self.assertTrue(entry["tenant_id"].startswith("synthetic-"))

    def test_price_plan_ids_are_synthetic(self):
        for entry in self.fixture["price_plans"]:
            self.assertTrue(entry["plan_id"].startswith("synthetic-"))

    def test_flags_build_and_resolve_as_configured(self):
        flags = MonetizationFlags(global_default=self.fixture["global_default_enabled"])
        for entry in self.fixture["tenant_overrides"]:
            flags.set_tenant_override(entry["tenant_id"], entry["enabled"])

        for entry in self.fixture["tenant_overrides"]:
            self.assertEqual(flags.is_monetization_enabled(entry["tenant_id"]), entry["enabled"])
        self.assertEqual(flags.is_monetization_enabled("synthetic-tenant-unlisted"), self.fixture["global_default_enabled"])

    def test_price_plan_versions_register_and_resolve(self):
        catalog = PricePlanCatalog()
        for entry in self.fixture["price_plans"]:
            catalog.register_version(PricePlan(**entry))
        self.assertEqual(len(catalog.versions_for("synthetic-plan-standard")), len(self.fixture["price_plans"]))


if __name__ == "__main__":
    unittest.main()
