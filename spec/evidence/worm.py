"""evidence.worm: provider-neutral write-once-read-many store contract.

Decision (docs/adr/0007-m6-evidence-resilience-dr.md): same provider
abstraction shape as every prior milestone (migration_studio.vault.VaultProvider,
ingestion.kafka.KafkaPublisher, gateway.auth.AuthProvider,
firmware.signing.SignatureVerifier) -- an abstract interface plus an
in-memory test double. A real WORM backend (S3 Object Lock, Azure
Immutable Blob, an on-prem WORM appliance) and its provider/region are
blocked pending M8's deployment-profile decisions; see docs/evidence.md.

WORM semantics modeled here, matching real object-lock systems:
- ``put`` is write-once: writing an already-used ``record_id`` raises
  ``DuplicateRecordError`` rather than overwriting.
- Legal hold is metadata, not content: ``set_legal_hold`` may flip the
  hold flag on a record without touching its payload or hash.
- ``expire`` is the only removal path, and is refused unconditionally
  while legal hold is set -- regardless of whether the retention period
  has elapsed -- matching real WORM systems where a legal hold overrides
  even an expired retention lock. Absent a hold, it is refused until
  ``as_of`` reaches the record's ``retention_until``.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from evidence.records import EvidenceRecord, verify_integrity


class UnknownRecordError(KeyError):
    pass


class DuplicateRecordError(ValueError):
    pass


class LegalHoldActiveError(RuntimeError):
    """Raised by ``expire`` when the record's legal hold is set. This is
    checked before, and independently of, the retention-elapsed check."""


class RetentionNotElapsedError(RuntimeError):
    """Raised by ``expire`` when ``as_of`` is before ``retention_until``."""


class IntegrityError(RuntimeError):
    """Raised when a record read back from the store fails its content
    hash check -- see evidence.records.verify_integrity."""


class WormStore(ABC):
    @abstractmethod
    def put(self, record: EvidenceRecord) -> None: ...

    @abstractmethod
    def get(self, record_id: str) -> EvidenceRecord: ...

    @abstractmethod
    def list_for_tenant(self, tenant_id: str) -> tuple[EvidenceRecord, ...]: ...

    @abstractmethod
    def set_legal_hold(self, record_id: str, *, held: bool) -> EvidenceRecord: ...

    @abstractmethod
    def expire(self, record_id: str, *, as_of: str) -> None: ...


class InMemoryWormStore(WormStore):
    """Test double: an in-memory dict standing in for a real WORM backend.
    No network, process, or filesystem I/O -- see scripts/check.py's
    no-forbidden-imports policy, applied to this package like every other
    domain package."""

    def __init__(self) -> None:
        self._records: dict[str, EvidenceRecord] = {}

    def put(self, record: EvidenceRecord) -> None:
        if record.record_id in self._records:
            raise DuplicateRecordError(f"Record '{record.record_id}' already exists (write-once store)")
        self._records[record.record_id] = record

    def get(self, record_id: str) -> EvidenceRecord:
        record = self._require(record_id)
        if not verify_integrity(record):
            raise IntegrityError(f"Record '{record_id}' failed its content-hash integrity check")
        return record

    def list_for_tenant(self, tenant_id: str) -> tuple[EvidenceRecord, ...]:
        return tuple(record for record in self._records.values() if record.tenant_id == tenant_id)

    def set_legal_hold(self, record_id: str, *, held: bool) -> EvidenceRecord:
        record = self._require(record_id)
        updated = EvidenceRecord(
            record_id=record.record_id,
            tenant_id=record.tenant_id,
            category=record.category,
            occurred_at=record.occurred_at,
            payload=record.payload,
            retention_until=record.retention_until,
            content_sha256=record.content_sha256,
            legal_hold=held,
        )
        self._records[record_id] = updated
        return updated

    def expire(self, record_id: str, *, as_of: str) -> None:
        record = self._require(record_id)
        if record.legal_hold:
            raise LegalHoldActiveError(f"Record '{record_id}' is under legal hold and cannot be expired")
        if as_of < record.retention_until:
            raise RetentionNotElapsedError(
                f"Record '{record_id}' retention runs until {record.retention_until}, not yet {as_of}"
            )
        del self._records[record_id]

    def _require(self, record_id: str) -> EvidenceRecord:
        try:
            return self._records[record_id]
        except KeyError as exc:
            raise UnknownRecordError(f"No such record '{record_id}'") from exc
