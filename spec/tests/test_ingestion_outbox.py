import unittest

from ingestion.outbox import OutboxStore


def _event(**overrides):
    base = {
        "schema_version": 1,
        "event_type": "TelemetryAccepted",
        "event_id": "synthetic-event-101",
        "tenant_id": "synthetic-tenant-a",
        "device_id": "synthetic-device-001",
        "occurred_at": "2026-01-01T00:00:00Z",
        "correlation_id": "synthetic-correlation-101",
        "payload": {"level_percent": 50.0, "temperature_c": 20.0, "battery_percent": 90},
    }
    base.update(overrides)
    return base


class OutboxStoreTests(unittest.TestCase):
    def test_commit_creates_raw_telemetry_and_outbox_row(self):
        store = OutboxStore()
        event = _event()
        outbox_event = store.commit_telemetry_event(event, authorized_tenant="synthetic-tenant-a")

        self.assertEqual(outbox_event.event_id, "synthetic-event-101")
        self.assertEqual(outbox_event.tenant_id, "synthetic-tenant-a")
        self.assertFalse(outbox_event.published)
        self.assertTrue(store.has_event("synthetic-event-101"))

        raw = store.get_raw_telemetry("synthetic-event-101")
        self.assertEqual(raw.device_id, "synthetic-device-001")
        self.assertEqual(raw.payload["level_percent"], 50.0)

    def test_commit_rejects_invalid_event_via_shared_contract(self):
        store = OutboxStore()
        bad_event = _event(payload={"level_percent": 200.0, "temperature_c": 20.0, "battery_percent": 90})
        with self.assertRaises(ValueError):
            store.commit_telemetry_event(bad_event, authorized_tenant="synthetic-tenant-a")
        self.assertFalse(store.has_event("synthetic-event-101"))

    def test_commit_rejects_tenant_mismatch(self):
        store = OutboxStore()
        event = _event()
        with self.assertRaises(ValueError):
            store.commit_telemetry_event(event, authorized_tenant="synthetic-tenant-b")

    def test_commit_is_idempotent_on_repeated_event_id(self):
        store = OutboxStore()
        event = _event()
        first = store.commit_telemetry_event(event, authorized_tenant="synthetic-tenant-a")
        second = store.commit_telemetry_event(event, authorized_tenant="synthetic-tenant-a")
        self.assertEqual(first, second)

    def test_commit_idempotent_even_with_altered_retry_payload(self):
        # Simulates a publisher/producer retry that resends the same
        # event_id: the second call must not re-validate or overwrite,
        # matching "duplicate delivery must be a no-op at ingestion".
        store = OutboxStore()
        event = _event()
        first = store.commit_telemetry_event(event, authorized_tenant="synthetic-tenant-a")

        retried = dict(event)
        retried["payload"] = {"level_percent": 999.0, "temperature_c": 20.0, "battery_percent": 90}
        second = store.commit_telemetry_event(retried, authorized_tenant="synthetic-tenant-a")

        self.assertEqual(first, second)
        self.assertEqual(store.get_raw_telemetry("synthetic-event-101").payload["level_percent"], 50.0)

    def test_pending_lists_only_unpublished_events(self):
        store = OutboxStore()
        store.commit_telemetry_event(_event(), authorized_tenant="synthetic-tenant-a")
        store.commit_telemetry_event(
            _event(event_id="synthetic-event-102", correlation_id="synthetic-correlation-102"),
            authorized_tenant="synthetic-tenant-a",
        )
        self.assertEqual({e.event_id for e in store.pending()}, {"synthetic-event-101", "synthetic-event-102"})

        store.mark_published("synthetic-event-101")
        self.assertEqual({e.event_id for e in store.pending()}, {"synthetic-event-102"})

    def test_mark_published_is_visible_on_the_stored_row(self):
        store = OutboxStore()
        store.commit_telemetry_event(_event(), authorized_tenant="synthetic-tenant-a")
        published = store.mark_published("synthetic-event-101")
        self.assertTrue(published.published)

    def test_get_raw_telemetry_missing_event_raises(self):
        store = OutboxStore()
        with self.assertRaises(KeyError):
            store.get_raw_telemetry("synthetic-event-missing")

    def test_has_event_false_for_unknown_id(self):
        store = OutboxStore()
        self.assertFalse(store.has_event("synthetic-event-unknown"))


if __name__ == "__main__":
    unittest.main()
