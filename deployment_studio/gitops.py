"""deployment_studio.gitops: drift detection between a plan's declared
desired state and a supplied observed-state snapshot.

Decision (docs/adr/0009-m8-deployment-studio.md): same shape as M3's
``migration_studio.shadow_parity`` and M6's ``evidence.dr`` -- a pure
comparison function, no real GitOps controller (ArgoCD/Flux) connection
and no live cluster read. ``observed_state`` is always caller-supplied;
this module never fetches it.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class DriftStatus(str, Enum):
    IN_SYNC = "in_sync"
    DRIFTED = "drifted"


@dataclass(frozen=True)
class FieldDrift:
    field_name: str
    desired_value: object
    observed_value: object


@dataclass(frozen=True)
class ReconciliationResult:
    plan_id: str
    status: DriftStatus
    differences: tuple[FieldDrift, ...] = ()

    @property
    def is_in_sync(self) -> bool:
        return self.status is DriftStatus.IN_SYNC


_MISSING = object()


def reconcile_desired_state(*, plan_id: str, desired_state: dict, observed_state: dict) -> ReconciliationResult:
    all_keys = sorted(set(desired_state) | set(observed_state))
    differences = tuple(
        FieldDrift(
            field_name=key,
            desired_value=desired_state.get(key, _MISSING),
            observed_value=observed_state.get(key, _MISSING),
        )
        for key in all_keys
        if desired_state.get(key, _MISSING) != observed_state.get(key, _MISSING)
    )
    status = DriftStatus.DRIFTED if differences else DriftStatus.IN_SYNC
    return ReconciliationResult(plan_id=plan_id, status=status, differences=differences)
