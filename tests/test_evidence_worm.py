import unittest

from evidence.records import EvidenceCategory, build_evidence_record
from evidence.worm import (
    DuplicateRecordError,
    InMemoryWormStore,
    LegalHoldActiveError,
    RetentionNotElapsedError,
    UnknownRecordError,
)


def _record(record_id="synthetic-evidence-001", **overrides):
    base = dict(
        record_id=record_id,
        tenant_id="synthetic-tenant-a",
        category=EvidenceCategory.RBAC_DECISION,
        occurred_at="2026-01-15T10:00:00Z",
        payload={"allowed": True},
        retention_until="2027-01-15T10:00:00Z",
    )
    base.update(overrides)
    return build_evidence_record(**base)


class PutGetTests(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryWormStore()

    def test_put_then_get_round_trips(self):
        self.store.put(_record())
        self.assertEqual(self.store.get("synthetic-evidence-001").record_id, "synthetic-evidence-001")

    def test_put_is_write_once(self):
        self.store.put(_record())
        with self.assertRaises(DuplicateRecordError):
            self.store.put(_record())

    def test_get_unknown_record_raises(self):
        with self.assertRaises(UnknownRecordError):
            self.store.get("synthetic-evidence-unknown")

    def test_list_for_tenant_is_isolated(self):
        self.store.put(_record(record_id="synthetic-evidence-a1", tenant_id="synthetic-tenant-a"))
        self.store.put(_record(record_id="synthetic-evidence-b1", tenant_id="synthetic-tenant-b"))
        tenant_a = self.store.list_for_tenant("synthetic-tenant-a")
        self.assertEqual([r.record_id for r in tenant_a], ["synthetic-evidence-a1"])


class LegalHoldFlagTests(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryWormStore()
        self.store.put(_record())

    def test_set_legal_hold_true_then_false(self):
        held = self.store.set_legal_hold("synthetic-evidence-001", held=True)
        self.assertTrue(held.legal_hold)
        released = self.store.set_legal_hold("synthetic-evidence-001", held=False)
        self.assertFalse(released.legal_hold)

    def test_set_legal_hold_does_not_change_payload_or_hash(self):
        original = self.store.get("synthetic-evidence-001")
        held = self.store.set_legal_hold("synthetic-evidence-001", held=True)
        self.assertEqual(held.payload, original.payload)
        self.assertEqual(held.content_sha256, original.content_sha256)

    def test_set_legal_hold_unknown_record_raises(self):
        with self.assertRaises(UnknownRecordError):
            self.store.set_legal_hold("synthetic-evidence-unknown", held=True)


class ExpireTests(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryWormStore()
        self.store.put(_record(retention_until="2027-01-15T10:00:00Z"))

    def test_cannot_expire_before_retention_elapses(self):
        with self.assertRaises(RetentionNotElapsedError):
            self.store.expire("synthetic-evidence-001", as_of="2026-06-01T00:00:00Z")

    def test_expires_once_retention_elapses(self):
        self.store.expire("synthetic-evidence-001", as_of="2027-06-01T00:00:00Z")
        with self.assertRaises(UnknownRecordError):
            self.store.get("synthetic-evidence-001")

    def test_legal_hold_blocks_expiry_even_after_retention_elapses(self):
        self.store.set_legal_hold("synthetic-evidence-001", held=True)
        with self.assertRaises(LegalHoldActiveError):
            self.store.expire("synthetic-evidence-001", as_of="2027-06-01T00:00:00Z")
        # Still retrievable -- the hold, not a coincidental retention gap, blocked it.
        self.assertEqual(self.store.get("synthetic-evidence-001").record_id, "synthetic-evidence-001")

    def test_releasing_hold_allows_expiry_again(self):
        self.store.set_legal_hold("synthetic-evidence-001", held=True)
        self.store.set_legal_hold("synthetic-evidence-001", held=False)
        self.store.expire("synthetic-evidence-001", as_of="2027-06-01T00:00:00Z")
        with self.assertRaises(UnknownRecordError):
            self.store.get("synthetic-evidence-001")

    def test_expire_unknown_record_raises(self):
        with self.assertRaises(UnknownRecordError):
            self.store.expire("synthetic-evidence-unknown", as_of="2027-06-01T00:00:00Z")


if __name__ == "__main__":
    unittest.main()
