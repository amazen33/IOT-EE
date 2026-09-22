import json
from pathlib import Path
import unittest

from ingestion.outbox import RawTelemetryRecord
from reporting.telemetry_summary import TelemetrySummaryProjector

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "reporting_telemetry.synthetic.json"


def _record(event_id, tenant_id, device_id, occurred_at, level, temperature, battery):
    return RawTelemetryRecord(
        event_id=event_id,
        tenant_id=tenant_id,
        device_id=device_id,
        occurred_at=occurred_at,
        payload={"level_percent": level, "temperature_c": temperature, "battery_percent": battery},
    )


class TelemetrySummaryProjectorTests(unittest.TestCase):
    def test_first_reading_has_no_consumption_rate(self):
        projector = TelemetrySummaryProjector()
        summary = projector.apply(
            _record("synthetic-event-001", "synthetic-tenant-a", "synthetic-device-001",
                    "2026-01-01T00:00:00Z", 60.0, 20.0, 90)
        )
        self.assertEqual(summary.sample_count, 1)
        self.assertIsNone(summary.consumption_rate_percent_per_hour)
        self.assertEqual(summary.min_level_percent, 60.0)
        self.assertEqual(summary.max_level_percent, 60.0)

    def test_second_reading_updates_min_max_and_consumption_rate(self):
        projector = TelemetrySummaryProjector()
        projector.apply(
            _record("synthetic-event-001", "synthetic-tenant-a", "synthetic-device-001",
                    "2026-01-01T00:00:00Z", 60.0, 20.0, 90)
        )
        summary = projector.apply(
            _record("synthetic-event-002", "synthetic-tenant-a", "synthetic-device-001",
                    "2026-01-01T01:00:00Z", 55.0, 20.5, 89)
        )
        self.assertEqual(summary.sample_count, 2)
        self.assertEqual(summary.min_level_percent, 55.0)
        self.assertEqual(summary.max_level_percent, 60.0)
        self.assertAlmostEqual(summary.consumption_rate_percent_per_hour, -5.0)

    def test_duplicate_event_id_is_a_no_op(self):
        projector = TelemetrySummaryProjector()
        record = _record("synthetic-event-001", "synthetic-tenant-a", "synthetic-device-001",
                          "2026-01-01T00:00:00Z", 60.0, 20.0, 90)
        first = projector.apply(record)
        second = projector.apply(record)

        self.assertIsNotNone(first)
        self.assertIsNone(second)
        self.assertEqual(projector.summary_for("synthetic-tenant-a", "synthetic-device-001").sample_count, 1)

    def test_tenants_are_isolated_in_the_projection(self):
        projector = TelemetrySummaryProjector()
        projector.apply(
            _record("synthetic-event-001", "synthetic-tenant-a", "synthetic-device-001",
                    "2026-01-01T00:00:00Z", 60.0, 20.0, 90)
        )
        projector.apply(
            _record("synthetic-event-002", "synthetic-tenant-b", "synthetic-device-001",
                    "2026-01-01T00:00:00Z", 10.0, 15.0, 50)
        )
        self.assertEqual(len(projector.summaries_for_tenant("synthetic-tenant-a")), 1)
        self.assertEqual(len(projector.summaries_for_tenant("synthetic-tenant-b")), 1)
        self.assertEqual(projector.summary_for("synthetic-tenant-a", "synthetic-device-001").latest_level_percent, 60.0)

    def test_devices_are_tracked_independently_within_a_tenant(self):
        projector = TelemetrySummaryProjector()
        projector.apply(
            _record("synthetic-event-001", "synthetic-tenant-a", "synthetic-device-001",
                    "2026-01-01T00:00:00Z", 60.0, 20.0, 90)
        )
        projector.apply(
            _record("synthetic-event-002", "synthetic-tenant-a", "synthetic-device-002",
                    "2026-01-01T00:00:00Z", 30.0, 22.0, 70)
        )
        self.assertEqual(len(projector.summaries_for_tenant("synthetic-tenant-a")), 2)

    def test_zero_elapsed_time_yields_no_consumption_rate(self):
        projector = TelemetrySummaryProjector()
        projector.apply(
            _record("synthetic-event-001", "synthetic-tenant-a", "synthetic-device-001",
                    "2026-01-01T00:00:00Z", 60.0, 20.0, 90)
        )
        summary = projector.apply(
            _record("synthetic-event-002", "synthetic-tenant-a", "synthetic-device-001",
                    "2026-01-01T00:00:00Z", 58.0, 20.0, 90)
        )
        self.assertIsNone(summary.consumption_rate_percent_per_hour)

    def test_summary_for_unknown_device_returns_none(self):
        projector = TelemetrySummaryProjector()
        self.assertIsNone(projector.summary_for("synthetic-tenant-a", "synthetic-device-unknown"))


class SyntheticFixtureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(FIXTURE_PATH.read_text())

    def test_fixture_ids_are_synthetic(self):
        for reading in self.fixture["readings"]:
            for field_name in ("event_id", "tenant_id", "device_id"):
                self.assertTrue(reading[field_name].startswith("synthetic-"))

    def test_fixture_projects_to_expected_summaries_including_idempotent_replay(self):
        projector = TelemetrySummaryProjector()
        for reading in self.fixture["readings"]:
            projector.apply(
                RawTelemetryRecord(
                    event_id=reading["event_id"], tenant_id=reading["tenant_id"],
                    device_id=reading["device_id"], occurred_at=reading["occurred_at"],
                    payload=reading["payload"],
                )
            )

        for key, expected in self.fixture["expected"].items():
            tenant_id, device_id = key.split(":")
            summary = projector.summary_for(tenant_id, device_id)
            self.assertIsNotNone(summary, f"no summary projected for {key}")
            self.assertEqual(summary.sample_count, expected["sample_count"])
            self.assertEqual(summary.latest_level_percent, expected["latest_level_percent"])
            self.assertEqual(summary.min_level_percent, expected["min_level_percent"])
            self.assertEqual(summary.max_level_percent, expected["max_level_percent"])
            self.assertEqual(summary.latest_temperature_c, expected["latest_temperature_c"])
            self.assertEqual(summary.latest_battery_percent, expected["latest_battery_percent"])
            self.assertEqual(summary.last_reading_at, expected["last_reading_at"])
            self.assertAlmostEqual(
                summary.consumption_rate_percent_per_hour, expected["consumption_rate_percent_per_hour"]
            )


if __name__ == "__main__":
    unittest.main()
