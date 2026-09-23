import unittest

from deployment_studio.audit import (
    DuplicateAuditEntryError,
    InvalidAuditEntryError,
    PlanAction,
    PlanAuditEntry,
    PlanAuditLog,
)


def _entry(**overrides):
    base = dict(
        entry_id="synthetic-plan-a-created", plan_id="synthetic-plan-a", tenant_id="synthetic-tenant-a",
        action=PlanAction.CREATED, actor_principal_id="synthetic-operator-001", occurred_at="2026-01-01T00:00:00Z",
    )
    base.update(overrides)
    return PlanAuditEntry(**base)


class PlanAuditEntryValidationTests(unittest.TestCase):
    def test_valid_entry_constructs(self):
        self.assertEqual(_entry().action, PlanAction.CREATED)

    def test_rejects_invalid_occurred_at(self):
        with self.assertRaises(InvalidAuditEntryError):
            _entry(occurred_at="not-a-timestamp")


class PlanAuditLogTests(unittest.TestCase):
    def setUp(self):
        self.log = PlanAuditLog()

    def test_record_then_entries_for_plan(self):
        self.log.record(_entry())
        self.assertEqual(len(self.log.entries_for_plan("synthetic-plan-a")), 1)

    def test_duplicate_entry_id_is_rejected(self):
        self.log.record(_entry())
        with self.assertRaises(DuplicateAuditEntryError):
            self.log.record(_entry())

    def test_entries_for_plan_are_ordered_and_scoped(self):
        self.log.record(_entry(entry_id="synthetic-plan-a-created", occurred_at="2026-01-01T00:00:00Z"))
        self.log.record(_entry(entry_id="synthetic-plan-a-validated", action=PlanAction.VALIDATED, occurred_at="2026-01-02T00:00:00Z"))
        self.log.record(_entry(entry_id="synthetic-plan-b-created", plan_id="synthetic-plan-b", occurred_at="2026-01-01T00:00:00Z"))
        entries = self.log.entries_for_plan("synthetic-plan-a")
        self.assertEqual([entry.action for entry in entries], [PlanAction.CREATED, PlanAction.VALIDATED])


if __name__ == "__main__":
    unittest.main()
