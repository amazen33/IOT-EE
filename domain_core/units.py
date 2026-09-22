"""Canonical units and time semantics for the domain core.

Decision (see docs/adr/0003-m2-domain-core.md): metric units and UTC
everywhere in storage and domain logic. Device-native units, if ever
needed, are converted to these at the ingestion boundary (M3) — this
module does not do unit conversion, only validation of the canonical
representation.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import math
import re

_UTC_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")


@dataclass(frozen=True)
class Percentage:
    value: float

    def __post_init__(self) -> None:
        if type(self.value) not in (int, float) or not math.isfinite(self.value):
            raise ValueError("Percentage must be a finite number")
        if not 0 <= self.value <= 100:
            raise ValueError("Percentage must be within [0, 100]")


@dataclass(frozen=True)
class Celsius:
    value: float

    def __post_init__(self) -> None:
        if type(self.value) not in (int, float) or not math.isfinite(self.value):
            raise ValueError("Temperature must be a finite number")
        if self.value < -273.15:
            raise ValueError("Temperature below absolute zero")


@dataclass(frozen=True)
class UtcTimestamp:
    """Canonical UTC instant, stored as the same 'YYYY-MM-DDTHH:MM:SSZ'
    string shape already used by the M0 telemetry contract
    (foundation/contracts.py), so both modules agree on wire format."""

    value: str

    def __post_init__(self) -> None:
        if not isinstance(self.value, str) or not _UTC_PATTERN.fullmatch(self.value):
            raise ValueError("UTC timestamp required (YYYY-MM-DDTHH:MM:SSZ)")
        try:
            datetime.fromisoformat(self.value.replace("Z", "+00:00"))
        except ValueError:
            raise ValueError("Invalid UTC timestamp") from None

    def __lt__(self, other: "UtcTimestamp") -> bool:
        return self._as_datetime() < other._as_datetime()

    def __le__(self, other: "UtcTimestamp") -> bool:
        return self._as_datetime() <= other._as_datetime()

    def _as_datetime(self) -> datetime:
        return datetime.fromisoformat(self.value.replace("Z", "+00:00"))

    @classmethod
    def now(cls) -> "UtcTimestamp":
        return cls(datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))

    def add_seconds(self, seconds: int) -> "UtcTimestamp":
        from datetime import timedelta

        shifted = self._as_datetime() + timedelta(seconds=seconds)
        return UtcTimestamp(shifted.strftime("%Y-%m-%dT%H:%M:%SZ"))
