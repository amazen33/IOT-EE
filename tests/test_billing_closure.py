import unittest

from billing.closure import (
    ClosedPeriod,
    DuplicatePeriodIdError,
    InvalidClosedPeriodError,
    PeriodAlreadyClosedError,
    PeriodLedger,
    UnknownPeriodError,
    reverse_closed_period,
)


def _closed(**overrides):
    base = dict(
        period_id="synthetic-period-001", tenant_id="synthetic-tenant-a",
        period_start="2026-01-01T00:00:00Z", period_end="2026-02-01T00:00:00Z",
        certified_device_count=4, price_per_device=2.5, currency="USD",
        total_amount=10.0, closed_at="2026-02-02T00:00:00Z",
    )
    base.update(overrides)
    return ClosedPeriod(**base)


class ClosedPeriodValidationTests(unittest.TestCase):
    def test_valid_period_constructs(self):
        self.assertFalse(_closed().is_reversal)

    def test_rejects_mismatched_total_amount(self):
        with self.assertRaises(InvalidClosedPeriodError):
            _closed(total_amount=999.0)

    def test_rejects_negative_total_amount_for_an_original_closure(self):
        with self.assertRaises(InvalidClosedPeriodError):
            _closed(certified_device_count=0, price_per_device=2.5, total_amount=-1.0)

    def test_reversal_must_negate_original_magnitude(self):
        with self.assertRaises(InvalidClosedPeriodError):
            _closed(reversal_of="synthetic-period-001", total_amount=10.0)  # should be -10.0

    def test_valid_reversal_constructs(self):
        reversal = _closed(period_id="synthetic-period-001-rev1", reversal_of="synthetic-period-001", total_amount=-10.0)
        self.assertTrue(reversal.is_reversal)


class PeriodLedgerTests(unittest.TestCase):
    def setUp(self):
        self.ledger = PeriodLedger()
        self.ledger.close_period(_closed())

    def test_get_returns_the_closed_period(self):
        self.assertEqual(self.ledger.get("synthetic-period-001").total_amount, 10.0)

    def test_get_unknown_period_raises(self):
        with self.assertRaises(UnknownPeriodError):
            self.ledger.get("synthetic-period-unknown")

    def test_closing_the_same_period_id_twice_raises(self):
        with self.assertRaises(DuplicatePeriodIdError):
            self.ledger.close_period(_closed())

    def test_closing_the_same_tenant_period_range_twice_under_a_new_id_raises(self):
        with self.assertRaises(PeriodAlreadyClosedError):
            self.ledger.close_period(_closed(period_id="synthetic-period-002"))

    def test_reversal_of_unknown_period_raises(self):
        with self.assertRaises(UnknownPeriodError):
            self.ledger.close_period(
                _closed(period_id="synthetic-period-orphan-rev", reversal_of="synthetic-period-unknown", total_amount=-10.0)
            )

    def test_net_amount_for_period_is_the_original_before_any_reversal(self):
        self.assertEqual(self.ledger.net_amount_for_period("synthetic-tenant-a", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"), 10.0)


class ReverseClosedPeriodTests(unittest.TestCase):
    def setUp(self):
        self.ledger = PeriodLedger()
        self.ledger.close_period(_closed())

    def test_reversal_negates_the_original_amount(self):
        reversal = reverse_closed_period(
            ledger=self.ledger, original_period_id="synthetic-period-001",
            reversal_period_id="synthetic-period-001-rev1", closed_at="2026-02-03T00:00:00Z",
        )
        self.assertEqual(reversal.total_amount, -10.0)
        self.assertEqual(reversal.reversal_of, "synthetic-period-001")

    def test_original_entry_is_unchanged_after_reversal(self):
        reverse_closed_period(
            ledger=self.ledger, original_period_id="synthetic-period-001",
            reversal_period_id="synthetic-period-001-rev1", closed_at="2026-02-03T00:00:00Z",
        )
        self.assertEqual(self.ledger.get("synthetic-period-001").total_amount, 10.0)

    def test_net_amount_is_zero_after_a_full_reversal(self):
        reverse_closed_period(
            ledger=self.ledger, original_period_id="synthetic-period-001",
            reversal_period_id="synthetic-period-001-rev1", closed_at="2026-02-03T00:00:00Z",
        )
        self.assertEqual(
            self.ledger.net_amount_for_period("synthetic-tenant-a", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"), 0.0
        )

    def test_reversing_a_reversal_is_rejected(self):
        reverse_closed_period(
            ledger=self.ledger, original_period_id="synthetic-period-001",
            reversal_period_id="synthetic-period-001-rev1", closed_at="2026-02-03T00:00:00Z",
        )
        with self.assertRaises(InvalidClosedPeriodError):
            reverse_closed_period(
                ledger=self.ledger, original_period_id="synthetic-period-001-rev1",
                reversal_period_id="synthetic-period-001-rev2", closed_at="2026-02-04T00:00:00Z",
            )

    def test_reversing_unknown_period_raises(self):
        with self.assertRaises(UnknownPeriodError):
            reverse_closed_period(
                ledger=self.ledger, original_period_id="synthetic-period-unknown",
                reversal_period_id="synthetic-period-x-rev1", closed_at="2026-02-03T00:00:00Z",
            )


if __name__ == "__main__":
    unittest.main()
