"""ingestion bounded context: transactional outbox.

Decision (docs/adr/0004-m3-ingestion-outbox-delivery.md): generic outbox
pattern -- a ``raw_telemetry`` table holds the validated reading, an
``outbox_event`` table holds the not-yet-published canonical event, and a
relay process (``OutboxRelay`` in kafka.py) publishes outbox rows to Kafka
and marks them sent. This module models both tables and the "commit" that
must write to both atomically, in memory, standing in for a single
PostgreSQL transaction -- no real database connection exists yet (M3's
"MQTT/PostgreSQL/Kafka/TB integration" gate item against a real, isolated
instance is a separate, currently blocked, verification step; see
docs/ingestion.md).

Idempotency: committing the same event_id twice is a no-op that returns
the original commit's result, modeling "publisher retries can duplicate
delivery" at the ingestion boundary itself, not just downstream.
"""

from __future__ import annotations

from dataclasses import dataclass

from foundation.contracts import validate_event


class OutboxCommitError(RuntimeError):
    """Raised when a commit cannot be completed atomically. In this
    in-memory model, a failure here means neither table was written --
    there is no partial state to roll back, which is exactly the guarantee
    a real transaction must preserve."""


@dataclass(frozen=True)
class RawTelemetryRecord:
    event_id: str
    tenant_id: str
    device_id: str
    occurred_at: str
    payload: dict


@dataclass(frozen=True)
class OutboxEvent:
    event_id: str
    tenant_id: str
    event_type: str
    payload: dict
    published: bool = False


class OutboxStore:
    """In-memory stand-in for the raw_telemetry + outbox_event tables and
    the transaction that writes to both."""

    def __init__(self) -> None:
        self._raw_telemetry: dict[str, RawTelemetryRecord] = {}
        self._outbox: dict[str, OutboxEvent] = {}

    def commit_telemetry_event(self, event: dict, *, authorized_tenant: str) -> OutboxEvent:
        """Validate the M0 telemetry contract, then atomically insert the
        raw reading and its outbox event. Committing an event_id already
        present is idempotent: returns the existing outbox row unchanged,
        rather than re-validating or re-inserting.
        """
        existing = self._outbox.get(event["event_id"]) if isinstance(event, dict) and "event_id" in event else None
        if existing is not None:
            return existing

        tenant_id, event_id = validate_event(event, authorized_tenant=authorized_tenant)

        raw = RawTelemetryRecord(
            event_id=event_id,
            tenant_id=tenant_id,
            device_id=event["device_id"],
            occurred_at=event["occurred_at"],
            payload=dict(event["payload"]),
        )
        outbox_event = OutboxEvent(
            event_id=event_id,
            tenant_id=tenant_id,
            event_type=event["event_type"],
            payload=dict(event["payload"]),
        )

        # Atomic in the only sense that matters for an in-memory model:
        # both dict writes happen, or (on an unexpected error) neither is
        # left in the store.
        try:
            self._raw_telemetry[event_id] = raw
            self._outbox[event_id] = outbox_event
        except Exception:
            self._raw_telemetry.pop(event_id, None)
            self._outbox.pop(event_id, None)
            raise OutboxCommitError("Failed to commit telemetry event atomically") from None

        return outbox_event

    def pending(self) -> list[OutboxEvent]:
        return [event for event in self._outbox.values() if not event.published]

    def mark_published(self, event_id: str) -> OutboxEvent:
        event = self._outbox[event_id]
        published_event = OutboxEvent(
            event_id=event.event_id, tenant_id=event.tenant_id,
            event_type=event.event_type, payload=event.payload, published=True,
        )
        self._outbox[event_id] = published_event
        return published_event

    def get_raw_telemetry(self, event_id: str) -> RawTelemetryRecord:
        return self._raw_telemetry[event_id]

    def has_event(self, event_id: str) -> bool:
        return event_id in self._outbox
