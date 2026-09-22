import json
from pathlib import Path
import unittest

from migration_studio.shadow_parity import (
    ParityStatus,
    ParityTolerance,
    compare_event,
    compare_events,
)

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "shadow_parity.synthetic.json"


def _canonical(**overrides):
    base = {
        "event_id": "synthetic-event-301",
        "device_id": "synthetic-device-001",
        "occurred_at": "2026-01-01T00:00:00Z",
        "payload": {"level_percent": 50.0, "temperature_c": 20.0, "battery_percent": 90},
    }
    base.update(overrides)
    return base


class CompareEventTests(unittest.TestCase):
    def test_identical_events_match(self):
        canonical = _canonical()
        shadow = _canonical()
        result = compare_event(canonical, shadow)
        self.assertEqual(result.status, ParityStatus.MATCH)
        self.assertTrue(result.is_match)
        self.assertEqual(result.differences, ())

    def test_missing_canonical_is_reported(self):
        shadow = _canonical()
        result = compare_event(None, shadow)
        self.assertEqual(result.status, ParityStatus.MISSING_CANONICAL)
        self.assertEqual(result.event_id, "synthetic-event-301")

    def test_missing_shadow_is_reported(self):
        canonical = _canonical()
        result = compare_event(canonical, None)
        self.assertEqual(result.status, ParityStatus.MISSING_SHADOW)

    def test_both_none_raises(self):
        with self.assertRaises(ValueError):
            compare_event(None, None)

    def test_mismatched_device_id_is_flagged(self):
        canonical = _canonical()
        shadow = _canonical(device_id="synthetic-device-002")
        result = compare_event(canonical, shadow)
        self.assertEqual(result.status, ParityStatus.MISMATCH)
        self.assertEqual({d.field_name for d in result.differences}, {"device_id"})

    def test_mismatched_timestamp_is_flagged(self):
        canonical = _canonical()
        shadow = _canonical(occurred_at="2026-01-01T00:05:00Z")
        result = compare_event(canonical, shadow)
        self.assertEqual(result.status, ParityStatus.MISMATCH)
        self.assertEqual({d.field_name for d in result.differences}, {"occurred_at"})

    def test_payload_numeric_mismatch_beyond_tolerance_is_flagged(self):
        canonical = _canonical()
        shadow = _canonical(payload={"level_percent": 55.0, "temperature_c": 20.0, "battery_percent": 90})
        result = compare_event(canonical, shadow)
        self.assertEqual(result.status, ParityStatus.MISMATCH)
        diff = next(d for d in result.differences if d.field_name == "payload.level_percent")
        self.assertAlmostEqual(diff.delta, 5.0)

    def test_payload_numeric_mismatch_within_tolerance_matches(self):
        canonical = _canonical()
        shadow = _canonical(payload={"level_percent": 50.2, "temperature_c": 20.0, "battery_percent": 90})
        tolerance = ParityTolerance(absolute={"payload.level_percent": 0.5})
        result = compare_event(canonical, shadow, tolerance=tolerance)
        self.assertEqual(result.status, ParityStatus.MATCH)

    def test_payload_missing_key_on_shadow_is_flagged(self):
        canonical = _canonical()
        shadow = _canonical(payload={"level_percent": 50.0, "temperature_c": 20.0})
        result = compare_event(canonical, shadow)
        self.assertEqual(result.status, ParityStatus.MISMATCH)
        self.assertTrue(any(d.field_name == "payload.battery_percent" for d in result.differences))

    def test_payload_non_numeric_mismatch_is_flagged_without_delta(self):
        canonical = _canonical(payload={"valve_open": True})
        shadow = _canonical(payload={"valve_open": False})
        result = compare_event(canonical, shadow)
        diff = next(d for d in result.differences if d.field_name == "payload.valve_open")
        self.assertIsNone(diff.delta)


class CompareEventsBatchTests(unittest.TestCase):
    def test_batch_reports_match_mismatch_and_missing(self):
        canonical_events = [
            _canonical(event_id="synthetic-event-301"),
            _canonical(event_id="synthetic-event-302"),
            _canonical(event_id="synthetic-event-303"),
        ]
        shadow_events = [
            _canonical(event_id="synthetic-event-301"),
            _canonical(event_id="synthetic-event-302", device_id="synthetic-device-999"),
            _canonical(event_id="synthetic-event-304"),
        ]
        report = compare_events(canonical_events, shadow_events)

        self.assertEqual(report.total, 4)
        self.assertEqual(report.matched, 1)
        self.assertEqual(report.mismatched, 1)
        self.assertEqual(report.missing_shadow, 1)
        self.assertEqual(report.missing_canonical, 1)
        self.assertFalse(report.all_matched)
        self.assertEqual(len(report.mismatches()), 3)

    def test_all_matched_true_when_every_event_agrees(self):
        events = [_canonical(event_id="synthetic-event-301")]
        report = compare_events(events, events)
        self.assertTrue(report.all_matched)

    def test_empty_batches_are_not_all_matched(self):
        report = compare_events([], [])
        self.assertEqual(report.total, 0)
        self.assertFalse(report.all_matched)


class SyntheticFixtureTests(unittest.TestCase):
    """Real ThingsBoard-side shadow capture is blocked pending the
    authorized export/inventory/approval workflow (docs/migration-studio.md,
    docs/ingestion.md); this exercises the comparison framework end-to-end
    against synthetic canonical/shadow pairs only."""

    def setUp(self):
        self.fixture = json.loads(FIXTURE_PATH.read_text())

    def test_fixture_ids_are_synthetic(self):
        for pair in self.fixture["pairs"]:
            for side in ("canonical", "shadow"):
                entry = pair.get(side)
                if entry is not None:
                    self.assertTrue(entry["event_id"].startswith("synthetic-"))

    def test_fixture_produces_expected_report_shape(self):
        canonical_events = [p["canonical"] for p in self.fixture["pairs"] if p.get("canonical") is not None]
        shadow_events = [p["shadow"] for p in self.fixture["pairs"] if p.get("shadow") is not None]

        report = compare_events(canonical_events, shadow_events)

        self.assertEqual(report.total, len(self.fixture["pairs"]))
        self.assertEqual(report.matched, self.fixture["expected"]["matched"])
        self.assertEqual(report.mismatched, self.fixture["expected"]["mismatched"])
        self.assertEqual(report.missing_canonical, self.fixture["expected"]["missing_canonical"])
        self.assertEqual(report.missing_shadow, self.fixture["expected"]["missing_shadow"])


if __name__ == "__main__":
    unittest.main()
