"""migration-studio bounded context, M3 slice: ThingsBoard shadow-parity
comparison framework (requirements-addendum.md M3 row: "TB shadow parity").

This module compares a canonical (Kafka/PostgreSQL-side) telemetry event
against a shadow reading captured from ThingsBoard, so that during
migration both systems can be shown to agree before any cutover decision
is made. It contains no ThingsBoard client, no authorized-export tooling,
and no real comparison data: real TB-side shadow capture is blocked
pending the authorized export/inventory/approval workflow described in
docs/migration-studio.md and docs/ingestion.md. Every comparison in this
module's tests runs against synthetic fixture data only.

Preserving TB rules and reading TB state happens only through the
authorized export path (migration_studio.sources); this module never
connects to a live ThingsBoard instance and never claims TB<->Kafka
atomicity -- it only reports observed differences between two already-
captured readings.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class ParityStatus(str, Enum):
    MATCH = "match"
    MISMATCH = "mismatch"
    MISSING_CANONICAL = "missing_canonical"
    MISSING_SHADOW = "missing_shadow"


@dataclass(frozen=True)
class ParityTolerance:
    """Per-field numeric tolerance for a parity comparison. Absent from a
    field's entry means exact-match is required for that field."""

    absolute: dict[str, float] = field(default_factory=dict)

    def allowed_delta(self, field_name: str) -> float:
        return self.absolute.get(field_name, 0.0)


@dataclass(frozen=True)
class FieldDifference:
    field_name: str
    canonical_value: object
    shadow_value: object
    delta: float | None = None


@dataclass(frozen=True)
class ParityResult:
    event_id: str
    status: ParityStatus
    differences: tuple[FieldDifference, ...] = ()

    @property
    def is_match(self) -> bool:
        return self.status == ParityStatus.MATCH


@dataclass(frozen=True)
class ParityReport:
    """Aggregate result of comparing a batch of canonical events against
    their TB shadow counterparts. Real TB-side data is currently blocked
    (see docs/ingestion.md); this report only reflects whatever synthetic
    or otherwise-supplied pairs it was given."""

    results: tuple[ParityResult, ...]

    @property
    def total(self) -> int:
        return len(self.results)

    @property
    def matched(self) -> int:
        return sum(1 for result in self.results if result.status == ParityStatus.MATCH)

    @property
    def mismatched(self) -> int:
        return sum(1 for result in self.results if result.status == ParityStatus.MISMATCH)

    @property
    def missing_canonical(self) -> int:
        return sum(1 for result in self.results if result.status == ParityStatus.MISSING_CANONICAL)

    @property
    def missing_shadow(self) -> int:
        return sum(1 for result in self.results if result.status == ParityStatus.MISSING_SHADOW)

    @property
    def all_matched(self) -> bool:
        return self.total > 0 and self.matched == self.total

    def mismatches(self) -> tuple[ParityResult, ...]:
        return tuple(result for result in self.results if result.status != ParityStatus.MATCH)


_COMPARABLE_FIELDS = ("device_id", "occurred_at", "payload")


def compare_event(
    canonical: dict | None,
    shadow: dict | None,
    *,
    tolerance: ParityTolerance | None = None,
) -> ParityResult:
    """Compares one canonical event to one TB shadow reading. Both are
    plain dicts shaped like the M0 telemetry contract (event_id,
    device_id, occurred_at, payload); neither is fetched by this
    function -- callers supply already-captured data (synthetic in every
    current test, since real TB export is blocked)."""

    tolerance = tolerance or ParityTolerance()

    if canonical is None and shadow is None:
        raise ValueError("At least one of canonical or shadow must be provided")
    if canonical is None:
        return ParityResult(event_id=shadow["event_id"], status=ParityStatus.MISSING_CANONICAL)
    if shadow is None:
        return ParityResult(event_id=canonical["event_id"], status=ParityStatus.MISSING_SHADOW)

    event_id = canonical["event_id"]
    differences: list[FieldDifference] = []

    for field_name in _COMPARABLE_FIELDS:
        canonical_value = canonical.get(field_name)
        shadow_value = shadow.get(field_name)

        if field_name == "payload":
            differences.extend(
                _compare_payload(canonical_value or {}, shadow_value or {}, tolerance)
            )
            continue

        if canonical_value != shadow_value:
            differences.append(
                FieldDifference(field_name=field_name, canonical_value=canonical_value, shadow_value=shadow_value)
            )

    if differences:
        return ParityResult(event_id=event_id, status=ParityStatus.MISMATCH, differences=tuple(differences))
    return ParityResult(event_id=event_id, status=ParityStatus.MATCH)


def _compare_payload(canonical_payload: dict, shadow_payload: dict, tolerance: ParityTolerance) -> list[FieldDifference]:
    differences: list[FieldDifference] = []
    keys = set(canonical_payload) | set(shadow_payload)
    for key in sorted(keys):
        canonical_value = canonical_payload.get(key)
        shadow_value = shadow_payload.get(key)
        field_name = f"payload.{key}"

        both_numeric = (
            isinstance(canonical_value, (int, float)) and not isinstance(canonical_value, bool)
            and isinstance(shadow_value, (int, float)) and not isinstance(shadow_value, bool)
        )
        if both_numeric:
            delta = abs(canonical_value - shadow_value)
            if delta > tolerance.allowed_delta(field_name):
                differences.append(
                    FieldDifference(
                        field_name=field_name, canonical_value=canonical_value,
                        shadow_value=shadow_value, delta=delta,
                    )
                )
        elif canonical_value != shadow_value:
            differences.append(
                FieldDifference(field_name=field_name, canonical_value=canonical_value, shadow_value=shadow_value)
            )

    return differences


def compare_events(
    canonical_events: list[dict],
    shadow_events: list[dict],
    *,
    tolerance: ParityTolerance | None = None,
) -> ParityReport:
    """Compares two batches of events by event_id, reporting a MISSING_*
    result for any event_id present on only one side."""

    canonical_by_id = {event["event_id"]: event for event in canonical_events}
    shadow_by_id = {event["event_id"]: event for event in shadow_events}
    all_ids = sorted(set(canonical_by_id) | set(shadow_by_id))

    results = tuple(
        compare_event(canonical_by_id.get(event_id), shadow_by_id.get(event_id), tolerance=tolerance)
        for event_id in all_ids
    )
    return ParityReport(results=results)
