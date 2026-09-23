"""evidence.capture: turn an already-produced domain decision into an
``EvidenceRecord``, without introducing any new I/O.

Decision (docs/adr/0007-m6-evidence-resilience-dr.md): this milestone's
evidence scope is the audit/compliance trail already produced by earlier
milestones -- RBAC authorization decisions (M2's domain_core.rbac),
firmware rollout/rollback state transitions (M5's firmware.rollout), and
ThingsBoard shadow-parity comparisons (M3's migration_studio.shadow_parity)
-- not raw telemetry or report snapshots (see docs/evidence.md's
non-goals). Each function here is a pure mapping from a domain object
already in hand to an ``EvidenceRecord``; none of them call a store,
generate an id, or produce a timestamp -- the caller supplies
``record_id``, ``occurred_at``, and ``retention_until``, keeping this
module free of clocks and ID generation, consistent with every other
domain package's non-goals.
"""

from __future__ import annotations

from domain_core.rbac import Principal
from evidence.records import EvidenceCategory, EvidenceRecord, build_evidence_record
from firmware.rollout import DeviceFirmwareState
from migration_studio.shadow_parity import ParityResult


def capture_rbac_decision(
    *,
    record_id: str,
    tenant_id: str,
    principal: Principal,
    permission: str,
    allowed: bool,
    occurred_at: str,
    retention_until: str,
) -> EvidenceRecord:
    payload = {
        "principal_id": principal.principal_id,
        "principal_kind": principal.kind.value,
        "permission": permission,
        "allowed": allowed,
    }
    return build_evidence_record(
        record_id=record_id,
        tenant_id=tenant_id,
        category=EvidenceCategory.RBAC_DECISION,
        occurred_at=occurred_at,
        payload=payload,
        retention_until=retention_until,
    )


def capture_rollout_event(
    *,
    record_id: str,
    tenant_id: str,
    artifact_id: str,
    device_state: DeviceFirmwareState,
    occurred_at: str,
    retention_until: str,
) -> EvidenceRecord:
    payload = {
        "artifact_id": artifact_id,
        "device_id": device_state.device_id,
        "ring": device_state.ring.value,
        "status": device_state.status.value,
        "current_version": device_state.current_version,
        "previous_version": device_state.previous_version,
    }
    return build_evidence_record(
        record_id=record_id,
        tenant_id=tenant_id,
        category=EvidenceCategory.FIRMWARE_ROLLOUT_EVENT,
        occurred_at=occurred_at,
        payload=payload,
        retention_until=retention_until,
    )


def capture_shadow_parity_comparison(
    *,
    record_id: str,
    tenant_id: str,
    result: ParityResult,
    occurred_at: str,
    retention_until: str,
) -> EvidenceRecord:
    payload = {
        "event_id": result.event_id,
        "status": result.status.value,
        "differences": [
            {
                "field_name": difference.field_name,
                "canonical_value": difference.canonical_value,
                "shadow_value": difference.shadow_value,
                "delta": difference.delta,
            }
            for difference in result.differences
        ],
    }
    return build_evidence_record(
        record_id=record_id,
        tenant_id=tenant_id,
        category=EvidenceCategory.SHADOW_PARITY_COMPARISON,
        occurred_at=occurred_at,
        payload=payload,
        retention_until=retention_until,
    )
