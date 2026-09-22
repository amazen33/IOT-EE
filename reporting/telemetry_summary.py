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
blocked.

Storage decision (repository owner, see docs/reporting.md): the read-model
is backed by ``sqlite3`` -- stdlib, no new dependency, no infrastructure
provisioned -- with a real ``CREATE TABLE`` and SQL-level idempotency,
rather than an in-memory Python dict. This is deliberately the one new
M4 module where persistence is modeled at all: domain_core, gateway, and
migration_studio must stay pure logic (enforced by
scripts/check.py's no-forbidden-imports policy), but a materialized
read-model's whole job is persistence, and a real schema here catches
shape mistakes now instead of at real-PostgreSQL integration time. A real
PostgreSQL-backed read-model table is still blocked -- this uses SQLite's
embedded engine (in-memory by default, or a file when ``db_path`` is
given), never a client/server database connection.

All computation stays in UTC (occurred_at strings, unchanged); tenant-local
display formatting is a presentation-only concern handled by
reporting.timezone, never by this module.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from datetime import datetime

from ingestion.outbox import RawTelemetryRecord

_SCHEMA = """
CREATE TABLE IF NOT EXISTS tank_telemetry_summary (
    tenant_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    sample_count INTEGER NOT NULL,
    latest_level_percent REAL NOT NULL,
    min_level_percent REAL NOT NULL,
    max_level_percent REAL NOT NULL,
    latest_temperature_c REAL NOT NULL,
    latest_battery_percent REAL NOT NULL,
    last_reading_at TEXT NOT NULL,
    consumption_rate_percent_per_hour REAL,
    PRIMARY KEY (tenant_id, device_id)
);

CREATE TABLE IF NOT EXISTS processed_telemetry_events (
    tenant_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);
"""


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


def _row_to_summary(row: tuple) -> TankTelemetrySummary:
    (
        tenant_id, device_id, sample_count, latest_level_percent, min_level_percent,
        max_level_percent, latest_temperature_c, latest_battery_percent, last_reading_at,
        consumption_rate_percent_per_hour,
    ) = row
    return TankTelemetrySummary(
        tenant_id=tenant_id,
        device_id=device_id,
        sample_count=sample_count,
        latest_level_percent=latest_level_percent,
        min_level_percent=min_level_percent,
        max_level_percent=max_level_percent,
        latest_temperature_c=latest_temperature_c,
        latest_battery_percent=latest_battery_percent,
        last_reading_at=last_reading_at,
        consumption_rate_percent_per_hour=consumption_rate_percent_per_hour,
    )


class TelemetrySummaryProjector:
    """SQLite-backed read-model store. ``db_path`` defaults to an
    isolated in-memory database (no file, no infrastructure); passing a
    path is supported for a future on-disk synthetic run but is not
    exercised by this milestone's tests. A real PostgreSQL-backed
    deployment is still blocked -- see docs/reporting.md."""

    def __init__(self, db_path: str = ":memory:") -> None:
        self._conn = sqlite3.connect(db_path)
        self._conn.executescript(_SCHEMA)
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()

    def __enter__(self) -> "TelemetrySummaryProjector":
        return self

    def __exit__(self, *exc_info) -> None:
        self.close()

    def apply(self, record: RawTelemetryRecord) -> TankTelemetrySummary | None:
        """Projects one raw telemetry row into the running summary for its
        (tenant_id, device_id). Returns None, without changing state, if
        this event_id was already applied for this tenant -- the same
        at-least-once-tolerant idempotency M3's consumer provides. The
        dedup check, the read of the previous summary, and both writes
        happen inside a single SQLite transaction: either the whole
        update lands, or none of it does.
        """
        with self._conn:
            already_processed = self._conn.execute(
                "SELECT 1 FROM processed_telemetry_events WHERE tenant_id = ? AND event_id = ?",
                (record.tenant_id, record.event_id),
            ).fetchone()
            if already_processed is not None:
                return None

            previous_row = self._conn.execute(
                "SELECT * FROM tank_telemetry_summary WHERE tenant_id = ? AND device_id = ?",
                (record.tenant_id, record.device_id),
            ).fetchone()
            previous = _row_to_summary(previous_row) if previous_row is not None else None

            level = float(record.payload["level_percent"])
            temperature = float(record.payload["temperature_c"])
            battery = float(record.payload["battery_percent"])

            consumption_rate = None
            if previous is not None:
                hours_elapsed = (
                    _parse(record.occurred_at) - _parse(previous.last_reading_at)
                ).total_seconds() / 3600.0
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

            self._conn.execute(
                """
                INSERT INTO tank_telemetry_summary (
                    tenant_id, device_id, sample_count, latest_level_percent, min_level_percent,
                    max_level_percent, latest_temperature_c, latest_battery_percent, last_reading_at,
                    consumption_rate_percent_per_hour
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, device_id) DO UPDATE SET
                    sample_count = excluded.sample_count,
                    latest_level_percent = excluded.latest_level_percent,
                    min_level_percent = excluded.min_level_percent,
                    max_level_percent = excluded.max_level_percent,
                    latest_temperature_c = excluded.latest_temperature_c,
                    latest_battery_percent = excluded.latest_battery_percent,
                    last_reading_at = excluded.last_reading_at,
                    consumption_rate_percent_per_hour = excluded.consumption_rate_percent_per_hour
                """,
                (
                    summary.tenant_id, summary.device_id, summary.sample_count, summary.latest_level_percent,
                    summary.min_level_percent, summary.max_level_percent, summary.latest_temperature_c,
                    summary.latest_battery_percent, summary.last_reading_at,
                    summary.consumption_rate_percent_per_hour,
                ),
            )
            self._conn.execute(
                "INSERT INTO processed_telemetry_events (tenant_id, event_id) VALUES (?, ?)",
                (record.tenant_id, record.event_id),
            )

        return summary

    def summary_for(self, tenant_id: str, device_id: str) -> TankTelemetrySummary | None:
        row = self._conn.execute(
            "SELECT * FROM tank_telemetry_summary WHERE tenant_id = ? AND device_id = ?",
            (tenant_id, device_id),
        ).fetchone()
        return _row_to_summary(row) if row is not None else None

    def summaries_for_tenant(self, tenant_id: str) -> list[TankTelemetrySummary]:
        rows = self._conn.execute(
            "SELECT * FROM tank_telemetry_summary WHERE tenant_id = ? ORDER BY device_id",
            (tenant_id,),
        ).fetchall()
        return [_row_to_summary(row) for row in rows]
