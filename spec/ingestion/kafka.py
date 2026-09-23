"""ingestion bounded context: outbox relay and consumer-side idempotency.

Decision (docs/adr/0004-m3-ingestion-outbox-delivery.md): dedup by
event_id, no cross-device ordering guarantee -- per-device ordering would
come from Kafka partitioning by device_id in a real deployment, which this
module does not implement (no real Kafka connection exists; see
docs/ingestion.md for what is blocked).

KafkaPublisher is a provider-neutral abstraction, same shape as
migration_studio.vault.VaultProvider: a real client (confluent-kafka,
aiokafka, etc.) is a future adapter with its own integration tests.
InMemoryKafkaPublisher is a test double only, and can simulate an
at-least-once publisher by delivering an event to subscribers more than
once -- consumers must tolerate that, not the publisher.
"""

from __future__ import annotations

from dataclasses import dataclass
from abc import ABC, abstractmethod

from ingestion.outbox import OutboxEvent, OutboxStore


@dataclass(frozen=True)
class DeliveredEvent:
    event_id: str
    tenant_id: str
    event_type: str
    payload: dict


class KafkaPublisher(ABC):
    """Provider-neutral publish interface. A real backend is a future
    adapter with its own integration tests; this module never opens a
    socket."""

    @abstractmethod
    def publish(self, event: OutboxEvent) -> None:
        ...


class InMemoryKafkaPublisher(KafkaPublisher):
    """Test double only. Records every publish call, including
    duplicates, so tests can simulate at-least-once delivery."""

    def __init__(self) -> None:
        self.delivered: list[DeliveredEvent] = []

    def publish(self, event: OutboxEvent) -> None:
        self.delivered.append(
            DeliveredEvent(
                event_id=event.event_id, tenant_id=event.tenant_id,
                event_type=event.event_type, payload=dict(event.payload),
            )
        )

    def redeliver(self, event_id: str) -> None:
        """Test helper: simulate a publisher retry redelivering an event
        that was already published once."""
        matches = [event for event in self.delivered if event.event_id == event_id]
        if not matches:
            raise ValueError(f"No prior delivery of '{event_id}' to redeliver")
        self.delivered.append(matches[0])


class OutboxRelay:
    """Publishes pending outbox rows and marks them sent. Safe to call
    repeatedly: an already-published row is never re-selected, so a crash
    between "publish" and "mark published" is the only way a duplicate
    reaches the publisher -- exactly the case consumers must dedup, not a
    bug in the relay."""

    def __init__(self, store: OutboxStore, publisher: KafkaPublisher) -> None:
        self._store = store
        self._publisher = publisher

    def publish_pending(self) -> list[OutboxEvent]:
        published = []
        for event in self._store.pending():
            self._publisher.publish(event)
            published.append(self._store.mark_published(event.event_id))
        return published


class IdempotentConsumer:
    """Tracks processed events scoped by (tenant, consumer_name, event_id)
    -- the scope the target architecture requires for durable idempotency,
    since publisher retries (or a relay crash-then-retry) can duplicate
    delivery."""

    def __init__(self, consumer_name: str) -> None:
        if not consumer_name:
            raise ValueError("consumer_name is required")
        self._consumer_name = consumer_name
        self._processed: set[tuple[str, str]] = set()

    def process_once(self, event: DeliveredEvent, handler) -> bool:
        """Calls ``handler(event)`` exactly once per (tenant, event_id) for
        this consumer, even if ``process_once`` is called again with a
        redelivered copy. Returns True if the handler ran, False if the
        event was a duplicate and was skipped."""
        key = (event.tenant_id, event.event_id)
        if key in self._processed:
            return False
        handler(event)
        self._processed.add(key)
        return True

    def has_processed(self, tenant_id: str, event_id: str) -> bool:
        return (tenant_id, event_id) in self._processed
