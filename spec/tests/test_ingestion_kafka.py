import unittest

from ingestion.kafka import DeliveredEvent, IdempotentConsumer, InMemoryKafkaPublisher, OutboxRelay
from ingestion.outbox import OutboxStore


def _event(**overrides):
    base = {
        "schema_version": 1,
        "event_type": "TelemetryAccepted",
        "event_id": "synthetic-event-201",
        "tenant_id": "synthetic-tenant-a",
        "device_id": "synthetic-device-001",
        "occurred_at": "2026-01-01T00:00:00Z",
        "correlation_id": "synthetic-correlation-201",
        "payload": {"level_percent": 50.0, "temperature_c": 20.0, "battery_percent": 90},
    }
    base.update(overrides)
    return base


class OutboxRelayTests(unittest.TestCase):
    def test_publish_pending_delivers_and_marks_published(self):
        store = OutboxStore()
        store.commit_telemetry_event(_event(), authorized_tenant="synthetic-tenant-a")
        publisher = InMemoryKafkaPublisher()
        relay = OutboxRelay(store, publisher)

        published = relay.publish_pending()

        self.assertEqual(len(published), 1)
        self.assertTrue(published[0].published)
        self.assertEqual(len(publisher.delivered), 1)
        self.assertEqual(publisher.delivered[0].event_id, "synthetic-event-201")
        self.assertEqual(store.pending(), [])

    def test_publish_pending_is_safe_to_call_repeatedly(self):
        store = OutboxStore()
        store.commit_telemetry_event(_event(), authorized_tenant="synthetic-tenant-a")
        publisher = InMemoryKafkaPublisher()
        relay = OutboxRelay(store, publisher)

        relay.publish_pending()
        second_pass = relay.publish_pending()

        self.assertEqual(second_pass, [])
        self.assertEqual(len(publisher.delivered), 1)

    def test_publish_pending_handles_multiple_events(self):
        store = OutboxStore()
        store.commit_telemetry_event(_event(), authorized_tenant="synthetic-tenant-a")
        store.commit_telemetry_event(
            _event(event_id="synthetic-event-202", correlation_id="synthetic-correlation-202"),
            authorized_tenant="synthetic-tenant-a",
        )
        publisher = InMemoryKafkaPublisher()
        relay = OutboxRelay(store, publisher)

        published = relay.publish_pending()
        self.assertEqual({e.event_id for e in published}, {"synthetic-event-201", "synthetic-event-202"})


class InMemoryKafkaPublisherTests(unittest.TestCase):
    def test_redeliver_duplicates_a_prior_publish(self):
        store = OutboxStore()
        store.commit_telemetry_event(_event(), authorized_tenant="synthetic-tenant-a")
        publisher = InMemoryKafkaPublisher()
        OutboxRelay(store, publisher).publish_pending()

        publisher.redeliver("synthetic-event-201")

        self.assertEqual(len(publisher.delivered), 2)
        self.assertEqual(publisher.delivered[0], publisher.delivered[1])

    def test_redeliver_unknown_event_raises(self):
        publisher = InMemoryKafkaPublisher()
        with self.assertRaises(ValueError):
            publisher.redeliver("synthetic-event-never-published")


class IdempotentConsumerTests(unittest.TestCase):
    def test_process_once_runs_handler_exactly_once(self):
        consumer = IdempotentConsumer("reporting-projector")
        event = DeliveredEvent(
            event_id="synthetic-event-201", tenant_id="synthetic-tenant-a",
            event_type="TelemetryAccepted", payload={"level_percent": 50.0},
        )
        calls = []

        first = consumer.process_once(event, calls.append)
        second = consumer.process_once(event, calls.append)

        self.assertTrue(first)
        self.assertFalse(second)
        self.assertEqual(len(calls), 1)
        self.assertTrue(consumer.has_processed("synthetic-tenant-a", "synthetic-event-201"))

    def test_process_once_scopes_dedup_by_tenant_and_event_id(self):
        consumer = IdempotentConsumer("reporting-projector")
        event_a = DeliveredEvent(
            event_id="synthetic-event-shared", tenant_id="synthetic-tenant-a",
            event_type="TelemetryAccepted", payload={},
        )
        event_b = DeliveredEvent(
            event_id="synthetic-event-shared", tenant_id="synthetic-tenant-b",
            event_type="TelemetryAccepted", payload={},
        )
        calls = []

        self.assertTrue(consumer.process_once(event_a, calls.append))
        self.assertTrue(consumer.process_once(event_b, calls.append))
        self.assertEqual(len(calls), 2)

    def test_separate_consumers_have_independent_dedup_state(self):
        event = DeliveredEvent(
            event_id="synthetic-event-201", tenant_id="synthetic-tenant-a",
            event_type="TelemetryAccepted", payload={},
        )
        consumer_one = IdempotentConsumer("reporting-projector")
        consumer_two = IdempotentConsumer("alarm-projector")

        self.assertTrue(consumer_one.process_once(event, lambda e: None))
        self.assertTrue(consumer_two.process_once(event, lambda e: None))

    def test_consumer_name_required(self):
        with self.assertRaises(ValueError):
            IdempotentConsumer("")

    def test_end_to_end_relay_then_idempotent_consume_with_redelivery(self):
        # Simulates the full at-least-once path: outbox -> relay -> broker
        # redelivery -> consumer dedup, matching the ADR's decision that
        # dedup happens at the consumer by event_id, not via QoS or a
        # per-device ordering guarantee.
        store = OutboxStore()
        store.commit_telemetry_event(_event(), authorized_tenant="synthetic-tenant-a")
        publisher = InMemoryKafkaPublisher()
        OutboxRelay(store, publisher).publish_pending()
        publisher.redeliver("synthetic-event-201")

        consumer = IdempotentConsumer("reporting-projector")
        handled = []
        for delivered in publisher.delivered:
            consumer.process_once(delivered, handled.append)

        self.assertEqual(len(publisher.delivered), 2)
        self.assertEqual(len(handled), 1)


if __name__ == "__main__":
    unittest.main()
