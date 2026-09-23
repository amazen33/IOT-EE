import json
from pathlib import Path
import unittest

from evidence.records import (
    EvidenceCategory,
    EvidenceRecord,
    RedactionError,
    build_evidence_record,
    compute_content_hash,
    verify_integrity,
)

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "evidence_records.synthetic.json"


def _record(**overrides):
    base = dict(
        record_id="synthetic-evidence-001",
        tenant_id="synthetic-tenant-a",
        category=EvidenceCategory.RBAC_DECISION,
        occurred_at="2026-01-15T10:00:00Z",
        payload={"principal_id": "synthetic-operator-001", "allowed": True},
        retention_until="2031-01-15T10:00:00Z",
    )
    base.update(overrides)
    return build_evidence_record(**base)


class EvidenceRecordConstructionTests(unittest.TestCase):
    def test_valid_record_constructs(self):
        record = _record()
        self.assertEqual(record.category, EvidenceCategory.RBAC_DECISION)
        self.assertFalse(record.legal_hold)

    def test_rejects_invalid_record_id(self):
        with self.assertRaises(ValueError):
            _record(record_id="bad id")

    def test_rejects_invalid_occurred_at(self):
        with self.assertRaises(ValueError):
            _record(occurred_at="not-a-timestamp")

    def test_rejects_invalid_retention_until(self):
        with self.assertRaises(ValueError):
            _record(retention_until="not-a-timestamp")

    def test_rejects_empty_payload(self):
        with self.assertRaises(ValueError):
            _record(payload={})

    def test_direct_construction_requires_matching_hash(self):
        with self.assertRaises(ValueError):
            EvidenceRecord(
                record_id="synthetic-evidence-001",
                tenant_id="synthetic-tenant-a",
                category=EvidenceCategory.RBAC_DECISION,
                occurred_at="2026-01-15T10:00:00Z",
                payload={"allowed": True},
                retention_until="2031-01-15T10:00:00Z",
                content_sha256="0" * 64,
            )


class RedactionTests(unittest.TestCase):
    def test_rejects_password_key(self):
        with self.assertRaises(RedactionError):
            _record(payload={"password": "hunter2"})

    def test_rejects_api_key_key(self):
        with self.assertRaises(RedactionError):
            _record(payload={"api_key": "abc"})

    def test_rejects_ssn_key(self):
        with self.assertRaises(RedactionError):
            _record(payload={"ssn": "123-45-6789"})

    def test_case_insensitive(self):
        with self.assertRaises(RedactionError):
            _record(payload={"Password": "hunter2"})

    def test_ordinary_key_is_accepted(self):
        record = _record(payload={"permission": "firmware.manage", "allowed": True})
        self.assertTrue(record.payload["allowed"])


class IntegrityTests(unittest.TestCase):
    def test_verify_integrity_true_for_untampered_record(self):
        record = _record()
        self.assertTrue(verify_integrity(record))

    def test_content_hash_is_stable_for_equal_payloads(self):
        self.assertEqual(compute_content_hash({"a": 1, "b": 2}), compute_content_hash({"b": 2, "a": 1}))

    def test_content_hash_differs_for_different_payloads(self):
        self.assertNotEqual(compute_content_hash({"a": 1}), compute_content_hash({"a": 2}))


class SyntheticFixtureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(FIXTURE_PATH.read_text())

    def test_fixture_ids_are_synthetic(self):
        for entry in self.fixture["records"]:
            self.assertTrue(entry["record_id"].startswith("synthetic-"))
            self.assertTrue(entry["tenant_id"].startswith("synthetic-"))

    def test_all_fixture_records_construct_and_verify(self):
        for entry in self.fixture["records"]:
            record = EvidenceRecord(
                record_id=entry["record_id"],
                tenant_id=entry["tenant_id"],
                category=EvidenceCategory(entry["category"]),
                occurred_at=entry["occurred_at"],
                payload=entry["payload"],
                retention_until=entry["retention_until"],
                content_sha256=entry["content_sha256"],
                legal_hold=entry["legal_hold"],
            )
            self.assertTrue(verify_integrity(record))


if __name__ == "__main__":
    unittest.main()
