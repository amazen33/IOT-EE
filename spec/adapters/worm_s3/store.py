"""adapters.worm_s3.store: a real S3-compatible WormStore.

Decision (docs/adr/0010-worm-s3-adapter-graduation.md): implements
evidence.worm.WormStore against a real S3-compatible bucket using
S3 Object Lock for legal hold and retention, rather than the in-memory
test double evidence.worm.InMemoryWormStore ships with. Scoped
explicitly to a *test* target (see worm_store_from_env's WORM_S3_BUCKET
env var and docs/adapters-worm-s3.md's non-goals) -- this is a real
backend integration, not a production deployment.

Key design points, each mechanically enforced by scripts/check.py:

- **boto3 is lazy-imported.** Importing this module (e.g. to reference
  S3WormStore in a type hint, or to construct one with an injected fake
  client for tests) never requires boto3 to be installed. Only actually
  constructing an S3WormStore without an injected client does, and it
  raises AdapterNotInstalledError with a clear installation instruction
  rather than a bare ImportError. See _check_boto3_imported_lazily in
  scripts/check.py.
- **boto3 is an optional extra, not a core dependency.**
  requirements.txt (ADR 0005's pure-data-only core) is untouched;
  requirements-adapters-s3.txt is a new, separately reviewed, pinned
  file. See _check_requirements_txt_has_no_boto3 and
  _check_boto3_confined_to_adapters in scripts/check.py.
- **Credentials are never read by this code.** boto3.client("s3", ...)
  resolves credentials through its own standard chain (environment
  variables, a shared credentials file, or an IAM role) -- this module
  passes only non-secret connection configuration (bucket, endpoint URL,
  region) and never touches a secret value itself, matching the
  project's enter-secrets-once-into-the-vault discipline.
- **Objects are keyed by record_id alone** (``<record_id>.json``), not
  tenant-prefixed, because evidence.worm.WormStore's ABC methods
  (get/set_legal_hold/expire) take only a record_id, with no tenant_id.
  ``list_for_tenant`` therefore lists the whole bucket and filters by the
  tenant_id recorded in each object's body -- a real, working
  implementation at test-bucket scale, not an indexed, production-scale
  one (see docs/adapters-worm-s3.md's non-goals).
- **Legal hold and retention are real S3 Object Lock state**, not fields
  inside the stored JSON body -- ``get`` reads the object's actual
  Object Lock legal-hold status back from S3, so a real held/not-held
  state is what's reported, not a value this code could get out of sync
  with the backend.
- **No production retention claim.** Object Lock is applied in
  GOVERNANCE mode, and ``expire``'s ``delete_object`` call, on a
  versioned bucket, creates a delete marker rather than guaranteeing
  physical removal of the prior version -- consistent with M6's already-
  documented "no production retention claims" limitation.
- **Duplicate-write rejection is atomic, not just checked-then-written.**
  ``put`` passes ``IfNoneMatch="*"`` on the ``put_object`` call itself,
  so a genuinely concurrent duplicate write is rejected by S3 at the
  point of write (HTTP 412, mapped to ``DuplicateRecordError``), not
  merely by an earlier ``head_object`` read that a second writer could
  race past. The ``head_object`` pre-check remains, but only as a
  fast-path optimization that avoids an unnecessary write attempt in
  the common (non-racing) case -- it is not itself relied on for
  correctness. This requires the configured S3-compatible endpoint to
  support conditional writes (``If-None-Match``); see
  docs/adapters-worm-s3.md's Known Limitations if that support cannot
  be confirmed for a given target.
"""

from __future__ import annotations

import json
import os
from datetime import datetime

from evidence.records import EvidenceCategory, EvidenceRecord, verify_integrity
from evidence.worm import (
    DuplicateRecordError,
    IntegrityError,
    LegalHoldActiveError,
    RetentionNotElapsedError,
    UnknownRecordError,
    WormStore,
)

# S3 Object Lock retention mode used for every record written by this
# adapter. GOVERNANCE (rather than COMPLIANCE) is a deliberate choice:
# COMPLIANCE-mode immutability is the kind of production compliance
# guarantee this project has repeatedly declined to claim before a real
# deployment decision (see docs/evidence.md's "no production retention
# claims"). GOVERNANCE still requires a real, separate
# s3:BypassGovernanceRetention permission to override, and this adapter
# never requests that permission or passes a bypass header.
_OBJECT_LOCK_MODE = "GOVERNANCE"

_NOT_FOUND_CODES = {"404", "NoSuchKey", "NotFound"}
_PRECONDITION_FAILED_CODES = {"PreconditionFailed", "412"}


class AdapterNotInstalledError(RuntimeError):
    """Raised when constructing an S3WormStore without an injected
    client and without the optional boto3 extra installed. Never a bare
    ImportError -- see this module's docstring."""


def _key(record_id: str) -> str:
    return f"{record_id}.json"


def _parse_iso8601(timestamp: str) -> datetime:
    return datetime.fromisoformat(timestamp.replace("Z", "+00:00"))


def _is_not_found(exc: Exception) -> bool:
    """Duck-typed rather than an isinstance check against
    botocore.exceptions.ClientError, deliberately: this lets a test
    double raise a lightweight exception with the same `.response` shape
    without ever needing botocore importable in the test process. A real
    boto3 ClientError satisfies this shape too."""
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return False
    return response.get("Error", {}).get("Code") in _NOT_FOUND_CODES


def _is_precondition_failed(exc: Exception) -> bool:
    """Duck-typed the same way as _is_not_found (see that function's
    docstring): matches a real boto3 ClientError's shape when a
    conditional put_object's IfNoneMatch precondition fails (HTTP 412),
    and lets a lightweight test double raise the same shape without
    needing botocore importable."""
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return False
    return response.get("Error", {}).get("Code") in _PRECONDITION_FAILED_CODES


def _record_to_body(record: EvidenceRecord) -> bytes:
    return json.dumps(
        {
            "record_id": record.record_id,
            "tenant_id": record.tenant_id,
            "category": record.category.value,
            "occurred_at": record.occurred_at,
            "payload": record.payload,
            "retention_until": record.retention_until,
            "content_sha256": record.content_sha256,
        },
        sort_keys=True,
    ).encode("utf-8")


def _body_to_record(raw: bytes, *, legal_hold: bool) -> EvidenceRecord:
    body = json.loads(raw)
    return EvidenceRecord(
        record_id=body["record_id"],
        tenant_id=body["tenant_id"],
        category=EvidenceCategory(body["category"]),
        occurred_at=body["occurred_at"],
        payload=body["payload"],
        retention_until=body["retention_until"],
        content_sha256=body["content_sha256"],
        legal_hold=legal_hold,
    )


class S3WormStore(WormStore):
    """A real S3-compatible WormStore. See this module's docstring for
    the design points that make it a real, but bounded and test-scoped,
    implementation."""

    def __init__(self, *, bucket: str, endpoint_url: str | None = None, region_name: str | None = None, client=None) -> None:
        if not bucket:
            raise ValueError("bucket is required")
        self._bucket = bucket
        self._client = client if client is not None else self._build_client(endpoint_url, region_name)

    @staticmethod
    def _build_client(endpoint_url: str | None, region_name: str | None):
        try:
            import boto3
        except ImportError as exc:
            raise AdapterNotInstalledError(
                "adapters.worm_s3.S3WormStore requires the optional 'boto3' extra. "
                "Install it with `pip install -r requirements-adapters-s3.txt`, or "
                "construct S3WormStore with an injected client (e.g. for tests)."
            ) from exc
        return boto3.client("s3", endpoint_url=endpoint_url, region_name=region_name)

    def put(self, record: EvidenceRecord) -> None:
        key = _key(record.record_id)
        # Fast-path, non-atomic pre-check: avoids an unnecessary write
        # attempt in the common (non-racing) case. This is NOT the
        # write-once guarantee -- a concurrent writer can race past it
        # between this read and the put_object call below. The actual
        # guarantee is the IfNoneMatch="*" conditional write further
        # down, which S3 (or a conditional-write-capable S3-compatible
        # endpoint) rejects atomically at the point of write.
        try:
            self._client.head_object(Bucket=self._bucket, Key=key)
        except Exception as exc:
            if not _is_not_found(exc):
                raise
        else:
            raise DuplicateRecordError(f"Record '{record.record_id}' already exists (write-once store)")

        try:
            self._client.put_object(
                Bucket=self._bucket,
                Key=key,
                Body=_record_to_body(record),
                ContentType="application/json",
                ObjectLockMode=_OBJECT_LOCK_MODE,
                ObjectLockRetainUntilDate=_parse_iso8601(record.retention_until),
                ObjectLockLegalHoldStatus="ON" if record.legal_hold else "OFF",
                IfNoneMatch="*",
            )
        except Exception as exc:
            if _is_precondition_failed(exc):
                raise DuplicateRecordError(
                    f"Record '{record.record_id}' already exists (write-once store; "
                    "detected by the atomic conditional write, not the pre-check)"
                ) from exc
            raise

    def get(self, record_id: str) -> EvidenceRecord:
        key = _key(record_id)
        try:
            body = self._client.get_object(Bucket=self._bucket, Key=key)["Body"].read()
        except Exception as exc:
            if _is_not_found(exc):
                raise UnknownRecordError(f"No such record '{record_id}'") from exc
            raise
        hold = self._client.get_object_legal_hold(Bucket=self._bucket, Key=key)
        held = hold.get("LegalHold", {}).get("Status") == "ON"
        record = _body_to_record(body, legal_hold=held)
        if not verify_integrity(record):
            raise IntegrityError(f"Record '{record_id}' failed its content-hash integrity check")
        return record

    def list_for_tenant(self, tenant_id: str) -> tuple[EvidenceRecord, ...]:
        # Known, documented limitation (docs/adapters-worm-s3.md): no
        # tenant-scoped secondary index exists, so this lists every
        # object in the bucket and filters client-side. Fine at
        # test-bucket scale; not a production-scale claim.
        records = []
        continuation_token = None
        while True:
            kwargs = {"Bucket": self._bucket}
            if continuation_token:
                kwargs["ContinuationToken"] = continuation_token
            page = self._client.list_objects_v2(**kwargs)
            for entry in page.get("Contents", []):
                key = entry["Key"]
                if not key.endswith(".json"):
                    continue
                record = self.get(key[: -len(".json")])
                if record.tenant_id == tenant_id:
                    records.append(record)
            if not page.get("IsTruncated"):
                break
            continuation_token = page.get("NextContinuationToken")
        return tuple(records)

    def set_legal_hold(self, record_id: str, *, held: bool) -> EvidenceRecord:
        key = _key(record_id)
        self.get(record_id)  # raises UnknownRecordError if missing
        self._client.put_object_legal_hold(
            Bucket=self._bucket, Key=key, LegalHold={"Status": "ON" if held else "OFF"}
        )
        return self.get(record_id)

    def expire(self, record_id: str, *, as_of: str) -> None:
        record = self.get(record_id)
        if record.legal_hold:
            raise LegalHoldActiveError(f"Record '{record_id}' is under legal hold and cannot be expired")
        # Parsed comparison, not raw string comparison: ISO-8601 permits
        # both a "Z" suffix and an explicit "+HH:MM"/"-HH:MM" offset (see
        # evidence.records._TIMESTAMP_PATTERN), and two timestamps in
        # different-but-equivalent forms do not compare correctly as
        # strings (e.g. "02:00:00+02:00" sorts after "00:00:00Z" even
        # though they are the same instant).
        if _parse_iso8601(as_of) < _parse_iso8601(record.retention_until):
            raise RetentionNotElapsedError(
                f"Record '{record_id}' retention runs until {record.retention_until}, not yet {as_of}"
            )
        # On a versioned bucket this creates a delete marker; the prior
        # object version remains subject to its own Object Lock
        # retention until a real lifecycle rule purges it. This adapter
        # does not claim permanent physical deletion.
        self._client.delete_object(Bucket=self._bucket, Key=_key(record_id))


def worm_store_from_env(*, client=None) -> S3WormStore:
    """Build an S3WormStore from non-secret connection configuration in
    the environment (WORM_S3_BUCKET, WORM_S3_ENDPOINT_URL,
    WORM_S3_REGION). Credentials are never read here -- see this
    module's docstring. Raises ValueError if WORM_S3_BUCKET is unset;
    tests/test_adapters_worm_s3_live.py skips before calling this in
    that case, so reaching this error means a live test was requested
    without configuring it, a real configuration mistake worth
    surfacing rather than silently working around."""
    bucket = os.environ.get("WORM_S3_BUCKET")
    if not bucket:
        raise ValueError("WORM_S3_BUCKET is not set; cannot build a real S3WormStore")
    return S3WormStore(
        bucket=bucket,
        endpoint_url=os.environ.get("WORM_S3_ENDPOINT_URL"),
        region_name=os.environ.get("WORM_S3_REGION", "us-east-1"),
        client=client,
    )
