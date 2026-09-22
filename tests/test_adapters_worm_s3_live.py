"""Real-backend test for adapters.worm_s3.S3WormStore against an actual
S3-compatible test bucket. Skipped -- reported as skipped, never as
passed -- unless WORM_S3_BUCKET is set, per the contract's requirement
to document unavailable external verification as blocked rather than
passed. This is the one test in the whole suite that performs real
network I/O; every other test in this repository (including every other
adapters.worm_s3 test) uses an in-memory fake.

To run this for real against a disposable test bucket (e.g. MinIO, or a
throwaway S3 bucket with Object Lock enabled at creation -- Object Lock
cannot be enabled after the fact):

    pip install -r requirements-adapters-s3.txt
    export WORM_S3_BUCKET=my-disposable-test-bucket
    export WORM_S3_ENDPOINT_URL=http://localhost:9000   # omit for real AWS S3
    export WORM_S3_REGION=us-east-1
    # AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (or an IAM role) resolve
    # through boto3's own standard credential chain -- never set or read
    # by this project's code.
    python -m unittest tests.test_adapters_worm_s3_live -v

Every record used here is synthetic and record_id is randomized per run
(a WORM store's write-once semantics mean re-running against the same
bucket with a fixed id would collide with a prior run's record)."""

import os
import unittest
import uuid

from evidence.records import EvidenceCategory, build_evidence_record
from evidence.worm import LegalHoldActiveError, RetentionNotElapsedError


@unittest.skipUnless(
    os.environ.get("WORM_S3_BUCKET"),
    "WORM_S3_BUCKET not set; live adapters.worm_s3 test skipped (blocked, not passed) -- see this file's docstring",
)
class LiveS3WormStoreTests(unittest.TestCase):
    def setUp(self):
        from adapters.worm_s3.store import worm_store_from_env

        self.store = worm_store_from_env()

    def test_real_put_get_hold_and_expire_roundtrip(self):
        record_id = f"synthetic-live-{uuid.uuid4().hex}"
        record = build_evidence_record(
            record_id=record_id,
            tenant_id="synthetic-tenant-live",
            category=EvidenceCategory.RBAC_DECISION,
            occurred_at="2026-01-01T00:00:00Z",
            payload={"decision": "granted", "test_run": "adapters.worm_s3 live gate"},
            retention_until="2026-01-01T00:00:01Z",  # already elapsed -- lets this test clean up after itself
        )

        self.store.put(record)
        fetched = self.store.get(record_id)
        self.assertEqual(fetched, record)
        self.assertFalse(fetched.legal_hold)

        held = self.store.set_legal_hold(record_id, held=True)
        self.assertTrue(held.legal_hold)
        with self.assertRaises(LegalHoldActiveError):
            self.store.expire(record_id, as_of="2099-01-01T00:00:00Z")

        self.store.set_legal_hold(record_id, held=False)
        with self.assertRaises(RetentionNotElapsedError):
            self.store.expire(record_id, as_of="2020-01-01T00:00:00Z")
        self.store.expire(record_id, as_of="2099-01-01T00:00:00Z")


if __name__ == "__main__":
    unittest.main()
