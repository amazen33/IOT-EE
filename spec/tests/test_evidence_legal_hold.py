import unittest

from domain_core.rbac import Principal, PrincipalKind
from evidence.records import EvidenceCategory, build_evidence_record
from evidence.legal_hold import LegalHoldPermissionError, set_legal_hold
from evidence.worm import InMemoryWormStore


def _record():
    return build_evidence_record(
        record_id="synthetic-evidence-001", tenant_id="synthetic-tenant-a",
        category=EvidenceCategory.RBAC_DECISION, occurred_at="2026-01-15T10:00:00Z",
        payload={"allowed": True}, retention_until="2027-01-15T10:00:00Z",
    )


def _principal_with(permissions):
    return Principal(
        principal_id="synthetic-operator-001", kind=PrincipalKind.TENANT_OPERATOR,
        tenant_id="synthetic-tenant-a", permissions=frozenset(permissions),
    )


class SetLegalHoldTests(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryWormStore()
        self.store.put(_record())

    def test_authorized_principal_can_set_hold(self):
        updated = set_legal_hold(
            principal=_principal_with({"evidence.legal_hold"}), store=self.store,
            record_id="synthetic-evidence-001", held=True,
        )
        self.assertTrue(updated.legal_hold)

    def test_authorized_principal_can_release_hold(self):
        set_legal_hold(principal=_principal_with({"evidence.legal_hold"}), store=self.store,
                        record_id="synthetic-evidence-001", held=True)
        updated = set_legal_hold(principal=_principal_with({"evidence.legal_hold"}), store=self.store,
                                  record_id="synthetic-evidence-001", held=False)
        self.assertFalse(updated.legal_hold)

    def test_unauthorized_principal_is_rejected(self):
        with self.assertRaises(LegalHoldPermissionError):
            set_legal_hold(principal=_principal_with(set()), store=self.store,
                            record_id="synthetic-evidence-001", held=True)

    def test_system_admin_may_set_hold(self):
        admin = Principal(principal_id="synthetic-admin-001", kind=PrincipalKind.SYSTEM_ADMIN, tenant_id=None)
        updated = set_legal_hold(principal=admin, store=self.store, record_id="synthetic-evidence-001", held=True)
        self.assertTrue(updated.legal_hold)


if __name__ == "__main__":
    unittest.main()
