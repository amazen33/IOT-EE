"""Contract-conformance tests for adapters.worm_s3.S3WormStore, run
against an in-memory FakeS3Client -- never against a real bucket, and
never requiring boto3 to be installed (the client is injected, so
S3WormStore's lazy `import boto3` is never reached). See
tests/test_adapters_worm_s3_live.py for the real-backend test, which
skips cleanly without WORM_S3_BUCKET set, and
tests/test_adapters_worm_s3_not_installed.py for the missing-boto3
behavior.

FakeS3Client raises exceptions shaped like botocore.exceptions.ClientError
(``.response == {"Error": {"Code": "..."}}``) so
adapters.worm_s3.store._is_not_found's duck-typed check exercises the
exact same path a real ClientError would take.
"""

import unittest

from adapters.worm_s3.store import S3WormStore
from evidence.records import EvidenceCategory, build_evidence_record
from evidence.worm import (
    DuplicateRecordError,
    LegalHoldActiveError,
    RetentionNotElapsedError,
    UnknownRecordError,
)


class _ClientError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.response = {"Error": {"Code": code}}


class FakeS3Client:
    """Minimal in-memory stand-in for the boto3 S3 client methods
    S3WormStore actually calls."""

    def __init__(self) -> None:
        self._objects: dict[str, bytes] = {}
        self._legal_hold: dict[str, str] = {}

    def head_object(self, *, Bucket, Key):
        if Key not in self._objects:
            raise _ClientError("404")
        return {}

    def get_object(self, *, Bucket, Key):
        if Key not in self._objects:
            raise _ClientError("NoSuchKey")
        return {"Body": _Readable(self._objects[Key])}

    def put_object(self, *, Bucket, Key, Body, ContentType, ObjectLockMode, ObjectLockRetainUntilDate, ObjectLockLegalHoldStatus):
        self._objects[Key] = Body
        self._legal_hold[Key] = ObjectLockLegalHoldStatus

    def get_object_legal_hold(self, *, Bucket, Key):
        return {"LegalHold": {"Status": self._legal_hold.get(Key, "OFF")}}

    def put_object_legal_hold(self, *, Bucket, Key, LegalHold):
        self._legal_hold[Key] = LegalHold["Status"]

    def delete_object(self, *, Bucket, Key):
        self._objects.pop(Key, None)
        self._legal_hold.pop(Key, None)

    def list_objects_v2(self, *, Bucket, ContinuationToken=None):
        return {"Contents": [{"Key": key} for key in self._objects], "IsTruncated": False}


class _Readable:
    def __init__(self, data: bytes) -> None:
        self._data = data

    def read(self) -> bytes:
        return self._data


def _store() -> tuple[S3WormStore, FakeS3Client]:
    client = FakeS3Client()
    return S3WormStore(bucket="synthetic-test-bucket", client=client), client


def _record(record_id="synthetic-record-a", tenant_id="synthetic-tenant-a", retention_until="2026-01-01T00:00:01Z"):
    return build_evidence_record(
        record_id=record_id,
        tenant_id=tenant_id,
        category=EvidenceCategory.RBAC_DECISION,
        occurred_at="2026-01-01T00:00:00Z",
        payload={"decision": "granted"},
        retention_until=retention_until,
    )


class PutGetRoundtripTests(unittest.TestCase):
    def test_put_then_get_roundtrips(self):
        store, _ = _store()
        record = _record()
        store.put(record)
        fetched = store.get(record.record_id)
        self.assertEqual(fetched, record)

    def test_duplicate_put_raises(self):
        store, _ = _store()
        store.put(_record())
        with self.assertRaises(DuplicateRecordError):
            store.put(_record())

    def test_get_unknown_raises(self):
        store, _ = _store()
        with self.assertRaises(UnknownRecordError):
            store.get("synthetic-does-not-exist")


class LegalHoldAndExpireTests(unittest.TestCase):
    def test_legal_hold_blocks_expire_regardless_of_retention(self):
        store, _ = _store()
        record = _record(retention_until="2020-01-01T00:00:00Z")  # already elapsed
        store.put(record)
        store.set_legal_hold(record.record_id, held=True)
        with self.assertRaises(LegalHoldActiveError):
            store.expire(record.record_id, as_of="2026-01-01T00:00:00Z")

    def test_expire_before_retention_raises(self):
        store, _ = _store()
        record = _record(retention_until="2099-01-01T00:00:00Z")
        store.put(record)
        with self.assertRaises(RetentionNotElapsedError):
            store.expire(record.record_id, as_of="2026-01-01T00:00:00Z")

    def test_expire_after_retention_and_no_hold_succeeds(self):
        store, _ = _store()
        record = _record(retention_until="2020-01-01T00:00:00Z")
        store.put(record)
        store.expire(record.record_id, as_of="2026-01-01T00:00:00Z")
        with self.assertRaises(UnknownRecordError):
            store.get(record.record_id)

    def test_set_legal_hold_reflects_in_a_subsequent_get(self):
        store, _ = _store()
        record = _record()
        store.put(record)
        held = store.set_legal_hold(record.record_id, held=True)
        self.assertTrue(held.legal_hold)
        self.assertTrue(store.get(record.record_id).legal_hold)


class ListForTenantTests(unittest.TestCase):
    def test_filters_by_tenant(self):
        store, _ = _store()
        store.put(_record(record_id="synthetic-a1", tenant_id="synthetic-tenant-a"))
        store.put(_record(record_id="synthetic-a2", tenant_id="synthetic-tenant-a"))
        store.put(_record(record_id="synthetic-b1", tenant_id="synthetic-tenant-b"))
        tenant_a = store.list_for_tenant("synthetic-tenant-a")
        self.assertEqual({r.record_id for r in tenant_a}, {"synthetic-a1", "synthetic-a2"})


if __name__ == "__main__":
    unittest.main()
