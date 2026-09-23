import unittest

from evidence.dr import (
    DrObjective,
    DrObjectiveCatalog,
    DrObjectiveError,
    UnknownTenantObjectiveError,
    reconcile_telemetry_summary,
    replay_telemetry_summary,
)
from ingestion.outbox import RawTelemetryRecord
from reporting.telemetry_summary import TelemetrySummaryProjector

_EVENTS = [
    RawTelemetryRecord(
        event_id="synthetic-event-601", tenant_id="synthetic-tenant-a", device_id="synthetic-device-001",
        occurred_at="2026-01-01T00:00:00Z", payload={"level_percent": 60.0, "temperature_c": 20.0, "battery_percent": 90},
    ),
    RawTelemetryRecord(
        event_id="synthetic-event-602", tenant_id="synthetic-tenant-a", device_id="synthetic-device-001",
        occurred_at="2026-01-01T01:00:00Z", payload={"level_percent": 55.0, "temperature_c": 20.5, "battery_percent": 89},
    ),
    RawTelemetryRecord(
        event_id="synthetic-event-603", tenant_id="synthetic-tenant-a", device_id="synthetic-device-002",
        occurred_at="2026-01-01T00:00:00Z", payload={"level_percent": 80.0, "temperature_c": 18.0, "battery_percent": 95},
    ),
]


class DrObjectiveTests(unittest.TestCase):
    def test_valid_objective_constructs(self):
        objective = DrObjective(tenant_id="synthetic-tenant-a", rpo_seconds=300, rto_seconds=1800)
        self.assertEqual(objective.rpo_seconds, 300)

    def test_rejects_non_positive_rpo(self):
        with self.assertRaises(DrObjectiveError):
            DrObjective(tenant_id="synthetic-tenant-a", rpo_seconds=0, rto_seconds=1800)

    def test_rejects_non_positive_rto(self):
        with self.assertRaises(DrObjectiveError):
            DrObjective(tenant_id="synthetic-tenant-a", rpo_seconds=300, rto_seconds=-1)

    def test_rejects_invalid_tenant_id(self):
        with self.assertRaises(DrObjectiveError):
            DrObjective(tenant_id="bad id", rpo_seconds=300, rto_seconds=1800)


class DrObjectiveCatalogTests(unittest.TestCase):
    def test_register_then_lookup(self):
        catalog = DrObjectiveCatalog()
        catalog.register(DrObjective(tenant_id="synthetic-tenant-a", rpo_seconds=300, rto_seconds=1800))
        self.assertEqual(catalog.for_tenant("synthetic-tenant-a").rto_seconds, 1800)

    def test_unknown_tenant_raises(self):
        catalog = DrObjectiveCatalog()
        with self.assertRaises(UnknownTenantObjectiveError):
            catalog.for_tenant("synthetic-tenant-unregistered")


class ReplayTelemetrySummaryTests(unittest.TestCase):
    def test_replay_reproduces_the_same_summary_as_sequential_apply(self):
        live = TelemetrySummaryProjector()
        try:
            for record in _EVENTS:
                live.apply(record)
            replayed = replay_telemetry_summary(_EVENTS)
            try:
                self.assertEqual(
                    live.summary_for("synthetic-tenant-a", "synthetic-device-001"),
                    replayed.summary_for("synthetic-tenant-a", "synthetic-device-001"),
                )
            finally:
                replayed.close()
        finally:
            live.close()


class ReconcileTelemetrySummaryTests(unittest.TestCase):
    def test_consistent_when_live_matches_replay(self):
        live = TelemetrySummaryProjector()
        try:
            for record in _EVENTS:
                live.apply(record)
            report = reconcile_telemetry_summary(tenant_id="synthetic-tenant-a", events=_EVENTS, live=live)
            self.assertTrue(report.is_consistent)
            self.assertEqual(set(report.device_ids_checked), {"synthetic-device-001", "synthetic-device-002"})
        finally:
            live.close()

    def test_detects_drift_when_live_is_missing_an_event(self):
        live = TelemetrySummaryProjector()
        try:
            live.apply(_EVENTS[0])  # only the first event -- live is "behind" durable events
            report = reconcile_telemetry_summary(tenant_id="synthetic-tenant-a", events=_EVENTS, live=live)
            self.assertFalse(report.is_consistent)
            drifted_devices = {difference.device_id for difference in report.differences}
            self.assertIn("synthetic-device-001", drifted_devices)
        finally:
            live.close()

    def test_scopes_to_the_requested_tenant_only(self):
        live = TelemetrySummaryProjector()
        try:
            for record in _EVENTS:
                live.apply(record)
            report = reconcile_telemetry_summary(tenant_id="synthetic-tenant-b", events=_EVENTS, live=live)
            self.assertEqual(report.device_ids_checked, ())
            self.assertTrue(report.is_consistent)
        finally:
            live.close()


if __name__ == "__main__":
    unittest.main()
