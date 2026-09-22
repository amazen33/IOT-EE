"""evidence.dr: documented RPO/RTO objectives and a replay/reconciliation
procedure -- this milestone's resilience/DR slice.

Decision (docs/adr/0007-m6-evidence-resilience-dr.md): RPO/RTO are
recorded as configurable, per-tenant, documented targets
(``DrObjective``); no real cross-region failover, backup/restore
execution, fault injection, or load/soak testing exists or is claimed
here (docs/test-plan.md's M6 row lists these as required future gates).
What this module does implement and test is a provider-neutral
replay/reconciliation procedure: rebuilding a
``reporting.telemetry_summary.TelemetrySummaryProjector`` read-model from
its durable source-of-truth events (``ingestion.outbox.RawTelemetryRecord``
rows) and comparing the rebuild against a live projector, the same way a
real recovery would be validated against an RPO/RTO target -- "did we
recover to the same state, and did we do it from durable events alone."

This is deliberately narrower than a real disaster-recovery drill: it
proves the *replay logic* is sound and deterministic, not that a real
backup exists, that a real failover completed within the RTO window, or
that a real backup/restore cycle preserves an RPO boundary. Those remain
blocked pending M8's environment/infrastructure decisions.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from ingestion.outbox import RawTelemetryRecord
from reporting.telemetry_summary import TankTelemetrySummary, TelemetrySummaryProjector

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")

_COMPARED_FIELDS = (
    "sample_count",
    "latest_level_percent",
    "min_level_percent",
    "max_level_percent",
    "latest_temperature_c",
    "latest_battery_percent",
    "last_reading_at",
    "consumption_rate_percent_per_hour",
)


class DrObjectiveError(ValueError):
    pass


@dataclass(frozen=True)
class DrObjective:
    """A tenant's documented recovery objectives. Structural validation
    only (positive integers, valid tenant id) -- meeting these targets
    against a real backup/restore or failover is not verified by this
    milestone; see the module docstring."""

    tenant_id: str
    rpo_seconds: int
    rto_seconds: int

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.tenant_id):
            raise DrObjectiveError("Invalid tenant_id")
        if self.rpo_seconds <= 0:
            raise DrObjectiveError("rpo_seconds must be positive")
        if self.rto_seconds <= 0:
            raise DrObjectiveError("rto_seconds must be positive")


class UnknownTenantObjectiveError(KeyError):
    pass


class DrObjectiveCatalog:
    """In-memory registry of per-tenant DR objectives. Standing in for
    wherever these targets are ultimately recorded (a config table, a
    tenant-settings service); no real backend exists yet."""

    def __init__(self) -> None:
        self._objectives: dict[str, DrObjective] = {}

    def register(self, objective: DrObjective) -> None:
        self._objectives[objective.tenant_id] = objective

    def for_tenant(self, tenant_id: str) -> DrObjective:
        try:
            return self._objectives[tenant_id]
        except KeyError as exc:
            raise UnknownTenantObjectiveError(f"No DR objective registered for tenant '{tenant_id}'") from exc


@dataclass(frozen=True)
class ReconciliationDifference:
    device_id: str
    field_name: str
    live_value: object
    replayed_value: object


@dataclass(frozen=True)
class ReconciliationReport:
    tenant_id: str
    device_ids_checked: tuple[str, ...] = ()
    differences: tuple[ReconciliationDifference, ...] = ()

    @property
    def is_consistent(self) -> bool:
        return not self.differences


def replay_telemetry_summary(events: list[RawTelemetryRecord]) -> TelemetrySummaryProjector:
    """Rebuilds a fresh, isolated read-model by replaying durable events
    through the same projection logic the live read-model uses. The
    caller owns the returned projector and must close it."""
    replayed = TelemetrySummaryProjector()
    for record in events:
        replayed.apply(record)
    return replayed


def _diff_summary(
    device_id: str, live: TankTelemetrySummary | None, replayed: TankTelemetrySummary | None
) -> tuple[ReconciliationDifference, ...]:
    if live is None and replayed is None:
        return ()
    if live is None or replayed is None:
        return (ReconciliationDifference(device_id=device_id, field_name="<summary>", live_value=live, replayed_value=replayed),)
    differences = []
    for field_name in _COMPARED_FIELDS:
        live_value = getattr(live, field_name)
        replayed_value = getattr(replayed, field_name)
        if live_value != replayed_value:
            differences.append(
                ReconciliationDifference(
                    device_id=device_id, field_name=field_name, live_value=live_value, replayed_value=replayed_value,
                )
            )
    return tuple(differences)


def reconcile_telemetry_summary(
    *, tenant_id: str, events: list[RawTelemetryRecord], live: TelemetrySummaryProjector
) -> ReconciliationReport:
    """Replays ``events`` into a fresh projector and compares its
    per-device summaries against ``live``'s, for every device that
    appears in ``events`` for ``tenant_id``. A clean report
    (``is_consistent``) means the live read-model is exactly what durable
    replay would produce -- the property an RPO/RTO-bounded recovery must
    hold."""
    replayed = replay_telemetry_summary(events)
    try:
        device_ids = tuple(sorted({record.device_id for record in events if record.tenant_id == tenant_id}))
        differences: list[ReconciliationDifference] = []
        for device_id in device_ids:
            differences.extend(
                _diff_summary(device_id, live.summary_for(tenant_id, device_id), replayed.summary_for(tenant_id, device_id))
            )
        return ReconciliationReport(tenant_id=tenant_id, device_ids_checked=device_ids, differences=tuple(differences))
    finally:
        replayed.close()
