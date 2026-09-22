"""reporting bounded context: CQRS read-model projection for the tank
telemetry summary report (repository-owner decision, see
docs/adr/0005-m4-gateway-reporting.md).

This projects M3's durable ``ingestion.outbox.RawTelemetryRecord`` rows
into a per-tenant, per-device summary. It deliberately consumes the
outbox's raw row rather than a ``ingestion.kafka.DeliveredEvent`` --
``OutboxEvent.payload`` is intentionally slim (only the inner telemetry
measurements, per M3's ADR), and does not itself carry ``device_id`` or
``occurred_at``. Wiring this projector as a genuine downstream Kafka
consumer would require enriching the published event envelope with those
fields, which is out of scope for this milestone and is not a defect in
the already-gated M3 outbox/relay: see docs/reporting.md for what is
blocked. Projecting off the outbox row still exercises the same
at-least-once, duplicate-tolerant delivery model M3 established --
``apply`` is idempotent per ``(tenant_id, event_id)``, exactly like
``ingestion.kafka.IdempotentConsumer``.

All computation stays in UTC (occurred_at strings, unchanged); tenant-local
display formatting is a presentation-only concern handled by
reporting.timezone, never by this module.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from ingestion.outbox import RawTelemetryRecord


@dataclass(frozen=True)
class TankTelemetrySummary:
    tenant_id: str
    device_id: str
    sample_count: int
    latest_level_percent: float
    min_level_percent: float
    max_level_percent: float
    latest_temperature_c: float
    latest_battery_percent: float
    last_reading_at: str
    consumption_rate_percent_per_hour: float | None = None


def _parse(occurred_at: str) -> datetime:
    return datetime.fromisoformat(occurred_at.replace("Z", "+00:00"))


class TelemetrySummaryProjector:
    """In-memory read-model store. A real deployment would materialize
    this into a PostgreSQL read-model table (M4's non-goal list, see
    docs/reporting.md); this models the projection logic itself, testable
    without a database."""

    def __init__(self) -> None:
        self._summaries: dict[tuple[str, str], TankTelemetrySummary] = {}
        self._processed_event_ids: set[tuple[str, str]] = set()

    def apply(self, record: RawTelemetryRecord) -> TankTelemetrySummary | None:
        """Projects one raw telemetry row into the running summary for its
        (tenant_id, device_id). Returns None, without changing state, if
        this event_id was already applied for this tenant -- the same
        at-least-once-tolerant idempotency M3's consumer provides."""
        dedup_key = (record.tenant_id, record.event_id)
        if dedup_key in self._processed_event_ids:
            return None

        key = (record.tenant_id, record.device_id)
        previous = self._summaries.get(key)
        level = float(record.payload["level_percent"])
        temperature = float(record.payload["temperature_c"])
        battery = float(record.payload["battery_percent"])

        consumption_rate = None
        if previous is not None:
            hours_elapsed = (_parse(record.occurred_at) - _parse(previous.last_reading_at)).total_seconds() / 3600.0
            if hours_elapsed > 0:
                consumption_rate = (level - previous.latest_level_percent) / hours_elapsed

        summary = TankTelemetrySummary(
            tenant_id=record.tenant_id,
            device_id=record.device_id,
            sample_count=1 if previous is None else previous.sample_count + 1,
            latest_level_percent=level,
            min_level_percent=level if previous is None else min(level, previous.min_level_percent),
            max_level_percent=level if previous is None else max(level, previous.max_level_percent),
            latest_temperature_c=temperature,
            latest_battery_percent=battery,
            last_reading_at=record.occurred_at,
            consumption_rate_percent_per_hour=consumption_rate,
        )
        self._summaries[key] = summary
        self._processed_event_ids.add(dedup_key)
        return summary

    def summary_for(self, tenant_id: str, device_id: str) -> TankTelemetrySummary | None:
        return self._summaries.get((tenant_id, device_id))

    def summaries_for_tenant(self, tenant_id: str) -> list[TankTelemetrySummary]:
        return [summary for (tid, _), summary in self._summaries.items() if tid == tenant_id]
