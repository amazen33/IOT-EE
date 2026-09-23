"""billing.pricing: effective-dated, versioned price plans.

Decision (docs/adr/0008-m7-monetization.md): a price plan is never
mutated once effective. Each ``PricePlan`` is a frozen, validated version
carrying its own ``effective_from`` date; ``PricePlanCatalog`` holds an
append-only, per-``plan_id`` history of versions and resolves "the price
in effect as of a given date" by picking the latest version whose
``effective_from`` is not after that date. Registering a version with an
``effective_from`` that already exists for that plan is rejected --
correcting a price means adding a new version with a new effective date,
never editing history.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_TIMESTAMP_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")
_CURRENCY_PATTERN = re.compile(r"[A-Z]{3}")


class InvalidPricePlanError(ValueError):
    pass


class DuplicatePlanVersionError(ValueError):
    pass


class UnknownPricePlanError(KeyError):
    pass


@dataclass(frozen=True)
class PricePlan:
    plan_id: str
    price_per_device: float
    currency: str
    effective_from: str

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.plan_id):
            raise InvalidPricePlanError("Invalid plan_id")
        if self.price_per_device < 0:
            raise InvalidPricePlanError("price_per_device cannot be negative")
        if not _CURRENCY_PATTERN.fullmatch(self.currency):
            raise InvalidPricePlanError("currency must be a 3-letter uppercase code")
        if not _TIMESTAMP_PATTERN.fullmatch(self.effective_from):
            raise InvalidPricePlanError("effective_from must be an ISO-8601 UTC timestamp")


class PricePlanCatalog:
    def __init__(self) -> None:
        self._versions: dict[str, list[PricePlan]] = {}

    def register_version(self, plan: PricePlan) -> None:
        versions = self._versions.setdefault(plan.plan_id, [])
        if any(existing.effective_from == plan.effective_from for existing in versions):
            raise DuplicatePlanVersionError(
                f"Plan '{plan.plan_id}' already has a version effective from {plan.effective_from}"
            )
        versions.append(plan)
        versions.sort(key=lambda version: version.effective_from)

    def plan_as_of(self, plan_id: str, as_of: str) -> PricePlan:
        applicable = [
            version for version in self._versions.get(plan_id, []) if version.effective_from <= as_of
        ]
        if not applicable:
            raise UnknownPricePlanError(f"No version of plan '{plan_id}' is effective as of {as_of}")
        return applicable[-1]

    def versions_for(self, plan_id: str) -> tuple[PricePlan, ...]:
        return tuple(self._versions.get(plan_id, ()))
