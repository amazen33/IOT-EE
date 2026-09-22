"""evidence.records: the immutable evidence-record shape.

Decision (docs/adr/0007-m6-evidence-resilience-dr.md): evidence this
milestone is the audit/compliance trail already produced by earlier
milestones' domain logic -- RBAC authorization decisions (M2), firmware
rollout/rollback events (M5), and ThingsBoard shadow-parity comparisons
(M3) -- not raw telemetry or report snapshots (deferred, see
docs/evidence.md's non-goals).

Two structural guarantees are enforced at construction, matching the
project's "structural impossibility over policy note" discipline
(domain_core.commands, firmware.rollout):

- **Redaction.** ``payload`` may not contain a raw-PII/secret-shaped key
  (password, token, secret, ssn, credit card, api key, ...). This is a
  mechanical guard against accidentally filing a raw secret into
  supposedly-redacted evidence, not a substitute for the tokenization/
  classification work tracked separately in the enterprise contract; a
  category-level allowlist mistake is still possible, but a literal
  ``payload={"password": "..."}`` is not.
- **Content hash.** ``content_sha256`` is computed once, at construction,
  from the payload's canonical JSON encoding. ``verify_integrity``
  recomputes it and compares -- catching any in-memory or serialized
  mutation of a record that is supposed to never change once written.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from enum import Enum

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_TIMESTAMP_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")

# Mechanical redaction guard: a payload key matching one of these (case
# insensitive, substring match) is rejected outright. Not a classification
# engine -- a last-line structural check that a raw secret/PII value never
# reaches an evidence record.
_FORBIDDEN_PAYLOAD_KEY_FRAGMENTS = (
    "password", "secret", "token", "api_key", "apikey", "ssn",
    "credit_card", "creditcard", "card_number", "bank_account",
    "private_key", "passphrase",
)


class RedactionError(ValueError):
    """Raised when a payload contains a raw-PII/secret-shaped key."""


class EvidenceCategory(str, Enum):
    RBAC_DECISION = "rbac_decision"
    FIRMWARE_ROLLOUT_EVENT = "firmware_rollout_event"
    SHADOW_PARITY_COMPARISON = "shadow_parity_comparison"


def compute_content_hash(payload: dict) -> str:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _check_redacted(payload: dict) -> None:
    for key in payload:
        lowered = str(key).lower()
        for fragment in _FORBIDDEN_PAYLOAD_KEY_FRAGMENTS:
            if fragment in lowered:
                raise RedactionError(
                    f"Evidence payload key '{key}' looks like raw PII/secret material; "
                    "redact it before filing evidence"
                )


@dataclass(frozen=True)
class EvidenceRecord:
    record_id: str
    tenant_id: str
    category: EvidenceCategory
    occurred_at: str
    payload: dict
    retention_until: str
    content_sha256: str
    legal_hold: bool = False

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.record_id):
            raise ValueError("Invalid record_id")
        if not _ID_PATTERN.fullmatch(self.tenant_id):
            raise ValueError("Invalid tenant_id")
        if not isinstance(self.category, EvidenceCategory):
            raise ValueError("Invalid evidence category")
        if not _TIMESTAMP_PATTERN.fullmatch(self.occurred_at):
            raise ValueError("occurred_at must be an ISO-8601 UTC timestamp")
        if not _TIMESTAMP_PATTERN.fullmatch(self.retention_until):
            raise ValueError("retention_until must be an ISO-8601 UTC timestamp")
        if not isinstance(self.payload, dict) or not self.payload:
            raise ValueError("payload must be a non-empty dict")
        _check_redacted(self.payload)
        expected_hash = compute_content_hash(self.payload)
        if self.content_sha256 != expected_hash:
            raise ValueError("content_sha256 does not match the payload it was computed from")


def build_evidence_record(
    *,
    record_id: str,
    tenant_id: str,
    category: EvidenceCategory,
    occurred_at: str,
    payload: dict,
    retention_until: str,
) -> EvidenceRecord:
    """Convenience constructor: computes ``content_sha256`` for the caller
    instead of requiring it be supplied (and possibly gotten wrong)."""
    return EvidenceRecord(
        record_id=record_id,
        tenant_id=tenant_id,
        category=category,
        occurred_at=occurred_at,
        payload=payload,
        retention_until=retention_until,
        content_sha256=compute_content_hash(payload),
    )


def verify_integrity(record: EvidenceRecord) -> bool:
    """Recomputes the payload hash and compares. A record that fails this
    check has been tampered with (or corrupted) since it was written --
    ``EvidenceRecord`` being frozen prevents in-process mutation, but this
    is the check a store applies to bytes it reads back from a real
    backend."""
    return compute_content_hash(record.payload) == record.content_sha256
