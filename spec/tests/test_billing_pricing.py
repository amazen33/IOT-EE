import unittest

from billing.pricing import (
    DuplicatePlanVersionError,
    InvalidPricePlanError,
    PricePlan,
    PricePlanCatalog,
    UnknownPricePlanError,
)


def _plan(**overrides):
    base = dict(plan_id="synthetic-plan-standard", price_per_device=2.5, currency="USD", effective_from="2026-01-01T00:00:00Z")
    base.update(overrides)
    return PricePlan(**base)


class PricePlanValidationTests(unittest.TestCase):
    def test_valid_plan_constructs(self):
        self.assertEqual(_plan().price_per_device, 2.5)

    def test_rejects_negative_price(self):
        with self.assertRaises(InvalidPricePlanError):
            _plan(price_per_device=-1.0)

    def test_rejects_lowercase_currency(self):
        with self.assertRaises(InvalidPricePlanError):
            _plan(currency="usd")

    def test_rejects_invalid_effective_from(self):
        with self.assertRaises(InvalidPricePlanError):
            _plan(effective_from="not-a-date")


class PricePlanCatalogTests(unittest.TestCase):
    def setUp(self):
        self.catalog = PricePlanCatalog()
        self.catalog.register_version(_plan(price_per_device=2.5, effective_from="2026-01-01T00:00:00Z"))
        self.catalog.register_version(_plan(price_per_device=3.0, effective_from="2026-06-01T00:00:00Z"))

    def test_plan_as_of_before_any_version_raises(self):
        with self.assertRaises(UnknownPricePlanError):
            self.catalog.plan_as_of("synthetic-plan-standard", "2025-12-31T00:00:00Z")

    def test_plan_as_of_selects_the_version_in_effect(self):
        self.assertEqual(self.catalog.plan_as_of("synthetic-plan-standard", "2026-03-01T00:00:00Z").price_per_device, 2.5)

    def test_plan_as_of_picks_up_a_later_version_once_effective(self):
        self.assertEqual(self.catalog.plan_as_of("synthetic-plan-standard", "2026-06-01T00:00:00Z").price_per_device, 3.0)
        self.assertEqual(self.catalog.plan_as_of("synthetic-plan-standard", "2026-12-01T00:00:00Z").price_per_device, 3.0)

    def test_unknown_plan_id_raises(self):
        with self.assertRaises(UnknownPricePlanError):
            self.catalog.plan_as_of("synthetic-plan-unregistered", "2026-03-01T00:00:00Z")

    def test_duplicate_effective_from_is_rejected(self):
        with self.assertRaises(DuplicatePlanVersionError):
            self.catalog.register_version(_plan(price_per_device=9.0, effective_from="2026-01-01T00:00:00Z"))

    def test_versions_for_returns_registered_versions(self):
        self.assertEqual(len(self.catalog.versions_for("synthetic-plan-standard")), 2)
        self.assertEqual(self.catalog.versions_for("synthetic-plan-unregistered"), ())

    def test_registering_a_correction_never_edits_history(self):
        original_versions = self.catalog.versions_for("synthetic-plan-standard")
        self.catalog.register_version(_plan(price_per_device=3.5, effective_from="2027-01-01T00:00:00Z"))
        self.assertEqual(self.catalog.versions_for("synthetic-plan-standard")[:2], original_versions)


if __name__ == "__main__":
    unittest.main()
