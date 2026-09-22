"""Append-only, hash-chained evidence log (in-memory, M1 foundation).

This is a foundation-level stand-in for the target architecture's
"append-only operational ledger plus WORM objects" (docs/architecture/
target-architecture.md, "Firmware, evidence, and monetization"). It is not
that system: it has no persistence, no WORM object storage, and no
retention/legal-hold controls. Those need explicit design and verification
at M6. What it does establish now, and enforces with tests, is the shape
every later evidence record must have: append-only, tamper-evident via hash
chaining, and never carrying a raw secret value.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
from typing import Any, Optional


def _canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), default=str)


@dataclass(frozen=True)
class EvidenceRecord:
    sequence: int
    actor: str
    action: str
    source_id: str
    from_status: Optional[str]
    to_status: Optional[str]
    config_snapshot: dict
    recorded_at: str
    previous_hash: str
    record_hash: str


class EvidenceLog:
    """Append-only in-process evidence log.

    Each record's hash covers the previous record's hash, so any
    modification or reordering of prior entries is detectable by
    recomputing the chain (see :meth:`verify`).
    """

    def __init__(self) -> None:
        self._records: list[EvidenceRecord] = []

    def record(
        self,
        *,
        actor: str,
        action: str,
        source_id: str,
        from_status: Optional[str],
        to_status: Optional[str],
        config_snapshot: dict,
    ) -> EvidenceRecord:
        if not actor or not action or not source_id:
            raise ValueError("actor, action, and source_id are required")
        previous_hash = self._records[-1].record_hash if self._records else "0" * 64
        payload = {
            "sequence": len(self._records),
            "actor": actor,
            "action": action,
            "source_id": source_id,
            "from_status": from_status,
            "to_status": to_status,
            "config_snapshot": config_snapshot,
            "recorded_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "previous_hash": previous_hash,
        }
        record_hash = hashlib.sha256(_canonical_json(payload).encode("utf-8")).hexdigest()
        record = EvidenceRecord(record_hash=record_hash, **payload)
        self._records.append(record)
        return record

    def __len__(self) -> int:
        return len(self._records)

    def __iter__(self):
        return iter(self._records)

    def verify(self) -> bool:
        """Recompute the hash chain; False means the log is not internally
        consistent (tampered, reordered, or corrupted)."""
        previous_hash = "0" * 64
        for record in self._records:
            if record.previous_hash != previous_hash:
                return False
            payload = {
                "sequence": record.sequence,
                "actor": record.actor,
                "action": record.action,
                "source_id": record.source_id,
                "from_status": record.from_status,
                "to_status": record.to_status,
                "config_snapshot": record.config_snapshot,
                "recorded_at": record.recorded_at,
                "previous_hash": record.previous_hash,
            }
            expected_hash = hashlib.sha256(_canonical_json(payload).encode("utf-8")).hexdigest()
            if expected_hash != record.record_hash:
                return False
            previous_hash = record.record_hash
        return True
