import unittest

from domain_core.rbac import Principal, PrincipalKind
from evidence.capture import (
    capture_rbac_decision,
    capture_rollout_event,
    capture_shadow_parity_comparison,
)
from evidence.records import EvidenceCategory
from firmware.rollout import DeviceFirmwareState, RolloutRing, RolloutStatus
from migration_studio.shadow_parity import FieldDifference, ParityResult, ParityStatus


class CaptureRbacDecisionTests(unittest.TestCase):
    def test_captures_principal_permission_and_outcome(self):
        principal = Principal(
            principal_id="synthetic-operator-001", kind=PrincipalKind.TENANT_OPERATOR,
            tenant_id="synthetic-tenant-a", permissions=frozenset({"firmware.manage"}),
        )
        record = capture_rbac_decision(
            record_id="synthetic-evidence-001", tenant_id="synthetic-tenant-a",
            principal=principal, permission="firmware.manage", allowed=True,
            occurred_at="2026-01-15T10:00:00Z", retention_until="2031-01-15T10:00:00Z",
        )
        self.assertEqual(record.category, EvidenceCategory.RBAC_DECISION)
        self.assertEqual(record.payload["principal_id"], "synthetic-operator-001")
        self.assertEqual(record.payload["permission"], "firmware.manage")
        self.assertTrue(record.payload["allowed"])


class CaptureRolloutEventTests(unittest.TestCase):
    def test_captures_device_rollback_state(self):
        state = DeviceFirmwareState(
            device_id="synthetic-device-001", ring=RolloutRing.CANARY, status=RolloutStatus.ROLLED_BACK,
            current_version="1.2.2", previous_version="1.2.2",
        )
        record = capture_rollout_event(
            record_id="synthetic-evidence-002", tenant_id="synthetic-tenant-a",
            artifact_id="synthetic-firmware-001", device_state=state,
            occurred_at="2026-01-16T08:30:00Z", retention_until="2031-01-16T08:30:00Z",
        )
        self.assertEqual(record.category, EvidenceCategory.FIRMWARE_ROLLOUT_EVENT)
        self.assertEqual(record.payload["status"], "rolled_back")
        self.assertEqual(record.payload["device_id"], "synthetic-device-001")


class CaptureShadowParityComparisonTests(unittest.TestCase):
    def test_captures_mismatch_differences(self):
        result = ParityResult(
            event_id="synthetic-event-001",
            status=ParityStatus.MISMATCH,
            differences=(FieldDifference(field_name="level_percent", canonical_value=41.2, shadow_value=40.9, delta=0.3),),
        )
        record = capture_shadow_parity_comparison(
            record_id="synthetic-evidence-003", tenant_id="synthetic-tenant-b", result=result,
            occurred_at="2026-01-17T12:00:00Z", retention_until="2026-02-17T12:00:00Z",
        )
        self.assertEqual(record.category, EvidenceCategory.SHADOW_PARITY_COMPARISON)
        self.assertEqual(record.payload["status"], "mismatch")
        self.assertEqual(len(record.payload["differences"]), 1)
        self.assertEqual(record.payload["differences"][0]["field_name"], "level_percent")


if __name__ == "__main__":
    unittest.main()
