"""billing.closure: immutable period closure and reversal-only correction.

Decision (docs/adr/0008-m7-monetization.md): closing a billing period
snapshots finalized usage and pricing into an immutable ``ClosedPeriod``.
``PeriodLedger`` is write-once per (tenant, period) for the original
closure -- closing the same period twice is rejected -- and the only way
to correct a closed period is a new, linked reversal entry
(``reverse_closed_period``) that negates the original amount; the
original entry is never edited or removed. This mirrors the "structural
impossibility over policy note" discipline used everywhere else in this
codebase (``domain_core.commands``, ``firmware.rollout``,
``evidence.worm``).
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_TIMESTAMP_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")
_CURRENCY_PATTERN = re.compile(r"[A-Z]{3}")
_CENTS = 1e-6


class InvalidClosedPeriodError(ValueError):
    pass


class PeriodAlreadyClosedError(ValueError):
    pass


class DuplicatePeriodIdError(ValueError):
    pass


class UnknownPeriodError(KeyError):
    pass


@dataclass(frozen=True)
class ClosedPeriod:
    period_id: str
    tenant_id: str
    period_start: str
    period_end: str
    certified_device_count: int
    price_per_device: float
    currency: str
    total_amount: float
    closed_at: str
    reversal_of: str | None = None

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.period_id):
            raise InvalidClosedPeriodError("Invalid period_id")
        if not _ID_PATTERN.fullmatch(self.tenant_id):
            raise InvalidClosedPeriodError("Invalid tenant_id")
        for field_name in (self.period_start, self.period_end, self.closed_at):
            if not _TIMESTAMP_PATTERN.fullmatch(field_name):
                raise InvalidClosedPeriodError("period_start/period_end/closed_at must be ISO-8601 UTC timestamps")
        if self.period_end <= self.period_start:
            raise InvalidClosedPeriodError("period_end must be after period_start")
        if self.certified_device_count < 0:
            raise InvalidClosedPeriodError("certified_device_count cannot be negative")
        if self.price_per_device < 0:
            raise InvalidClosedPeriodError("price_per_device cannot be negative")
        if not _CURRENCY_PATTERN.fullmatch(self.currency):
            raise InvalidClosedPeriodError("currency must be a 3-letter uppercase code")
        if self.reversal_of is not None and not _ID_PATTERN.fullmatch(self.reversal_of):
            raise InvalidClosedPeriodError("Invalid reversal_of period_id")
        expected_magnitude = round(self.price_per_device * self.certified_device_count, 2)
        if self.reversal_of is None:
            if self.total_amount < 0 or abs(self.total_amount - expected_magnitude) > _CENTS:
                raise InvalidClosedPeriodError("total_amount must equal price_per_device * certified_device_count")
        else:
            if abs(self.total_amount - (-expected_magnitude)) > _CENTS:
                raise InvalidClosedPeriodError("A reversal's total_amount must negate the original magnitude")

    @property
    def is_reversal(self) -> bool:
        return self.reversal_of is not None


class PeriodLedger:
    """Append-only, write-once-per-original-closure ledger. No update or
    delete method exists on this class at all -- correction is always a
    new entry via ``reverse_closed_period``."""

    def __init__(self) -> None:
        self._entries: dict[str, ClosedPeriod] = {}

    def close_period(self, closed: ClosedPeriod) -> None:
        if closed.period_id in self._entries:
            raise DuplicatePeriodIdError(f"period_id '{closed.period_id}' already exists (write-once ledger)")
        if closed.reversal_of is None:
            if any(
                entry.reversal_of is None
                and entry.tenant_id == closed.tenant_id
                and entry.period_start == closed.period_start
                and entry.period_end == closed.period_end
                for entry in self._entries.values()
            ):
                raise PeriodAlreadyClosedError(
                    f"Tenant '{closed.tenant_id}' already has a closed period for "
                    f"{closed.period_start}..{closed.period_end}"
                )
        else:
            if closed.reversal_of not in self._entries:
                raise UnknownPeriodError(f"Cannot reverse unknown period_id '{closed.reversal_of}'")
        self._entries[closed.period_id] = closed

    def get(self, period_id: str) -> ClosedPeriod:
        try:
            return self._entries[period_id]
        except KeyError as exc:
            raise UnknownPeriodError(f"No such closed period '{period_id}'") from exc

    def entries_for_period(self, tenant_id: str, period_start: str, period_end: str) -> tuple[ClosedPeriod, ...]:
        matches = [
            entry
            for entry in self._entries.values()
            if entry.tenant_id == tenant_id
            and (
                (entry.reversal_of is None and entry.period_start == period_start and entry.period_end == period_end)
                or (entry.reversal_of is not None and self._entries[entry.reversal_of].period_start == period_start
                    and self._entries[entry.reversal_of].period_end == period_end)
            )
        ]
        return tuple(sorted(matches, key=lambda entry: entry.closed_at))

    def net_amount_for_period(self, tenant_id: str, period_start: str, period_end: str) -> float:
        return round(sum(entry.total_amount for entry in self.entries_for_period(tenant_id, period_start, period_end)), 2)


def reverse_closed_period(*, ledger: PeriodLedger, original_period_id: str, reversal_period_id: str, closed_at: str) -> ClosedPeriod:
    original = ledger.get(original_period_id)
    if original.is_reversal:
        raise InvalidClosedPeriodError("Cannot reverse a reversal entry; reverse the original period")
    reversal = ClosedPeriod(
        period_id=reversal_period_id,
        tenant_id=original.tenant_id,
        period_start=original.period_start,
        period_end=original.period_end,
        certified_device_count=original.certified_device_count,
        price_per_device=original.price_per_device,
        currency=original.currency,
        total_amount=-original.total_amount,
        closed_at=closed_at,
        reversal_of=original.period_id,
    )
    ledger.close_period(reversal)
    return reversal
