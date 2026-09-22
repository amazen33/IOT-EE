import unittest

from domain_core.rbac import Principal, PrincipalKind
from firmware.provenance import FirmwareArtifact, ProvenanceError
from firmware.rollout import (
    DeviceNotInRolloutError,
    FirmwarePermissionError,
    FirmwareRollout,
    RolloutBlockedError,
    RolloutRing,
    RolloutStatus,
)
from firmware.signing import InMemorySignatureVerifier


def _artifact():
    return FirmwareArtifact(
        artifact_id="synthetic-firmware-001",
        version="1.2.3",
        content_sha256="5a413f02e1b4993a25a0bb04795d3539ff2e9b1ab17ae7d68607c39618b2933c",
        signer_id="synthetic-signer-001",
        build_source_ref="git:synthetic-commit-abc123",
        signature="7d695edc9baad624b79c68c5dd8df6a3964957db202af8c16ec815b53102137c",
    )


def _verifier():
    verifier = InMemorySignatureVerifier()
    verifier.register_signer("synthetic-signer-001", "synthetic-shared-secret-001")
    return verifier


def _manager(tenant_id="synthetic-tenant-a"):
    return Principal(
        principal_id="synthetic-operator-001", kind=PrincipalKind.TENANT_OPERATOR,
        tenant_id=tenant_id, permissions=frozenset({"firmware.manage"}),
    )


def _unauthorized_principal(tenant_id="synthetic-tenant-a"):
    return Principal(
        principal_id="synthetic-operator-002", kind=PrincipalKind.TENANT_OPERATOR,
        tenant_id=tenant_id, permissions=frozenset(),
    )


class FirmwareRolloutConstructionTests(unittest.TestCase):
    def test_cannot_construct_with_unverifiable_artifact(self):
        tampered = FirmwareArtifact(
            artifact_id="synthetic-firmware-001", version="1.2.3",
            content_sha256="5a413f02e1b4993a25a0bb04795d3539ff2e9b1ab17ae7d68607c39618b2933c",
            signer_id="synthetic-signer-001", build_source_ref="git:synthetic-commit-abc123",
            signature="0" * 64,
        )
        with self.assertRaises(ProvenanceError):
            FirmwareRollout(tampered, verifier=_verifier())

    def test_rings_must_be_non_empty(self):
        with self.assertRaises(ValueError):
            FirmwareRollout(_artifact(), verifier=_verifier(), rings=())

    def test_default_starting_ring_is_canary(self):
        rollout = FirmwareRollout(_artifact(), verifier=_verifier())
        self.assertEqual(rollout.current_ring(), RolloutRing.CANARY)


class AssignAndDeliverTests(unittest.TestCase):
    def setUp(self):
        self.rollout = FirmwareRollout(_artifact(), verifier=_verifier())

    def test_assign_device_requires_firmware_manage_permission(self):
        with self.assertRaises(FirmwarePermissionError):
            self.rollout.assign_device(
                principal=_unauthorized_principal(), device_id="synthetic-device-001",
                ring=RolloutRing.CANARY, current_version="1.2.2",
            )

    def test_assign_device_to_ring_not_in_rollout_raises(self):
        narrow_rollout = FirmwareRollout(_artifact(), verifier=_verifier(), rings=(RolloutRing.CANARY,))
        with self.assertRaises(ValueError):
            narrow_rollout.assign_device(
                principal=_manager(), device_id="synthetic-device-001",
                ring=RolloutRing.BROAD, current_version="1.2.2",
            )

    def test_assign_then_deliver_moves_to_in_progress(self):
        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-001",
            ring=RolloutRing.CANARY, current_version="1.2.2",
        )
        delivered = self.rollout.deliver_to_ring(principal=_manager(), ring=RolloutRing.CANARY)
        self.assertEqual(len(delivered), 1)
        self.assertEqual(delivered[0].status, RolloutStatus.IN_PROGRESS)
        self.assertEqual(delivered[0].current_version, "1.2.3")
        self.assertEqual(delivered[0].previous_version, "1.2.2")

    def test_deliver_only_affects_the_named_ring(self):
        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-canary",
            ring=RolloutRing.CANARY, current_version="1.2.2",
        )
        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-broad",
            ring=RolloutRing.BROAD, current_version="1.2.2",
        )
        delivered = self.rollout.deliver_to_ring(principal=_manager(), ring=RolloutRing.CANARY)
        self.assertEqual([state.device_id for state in delivered], ["synthetic-device-canary"])
        self.assertEqual(self.rollout.state_for("synthetic-device-broad").status, RolloutStatus.ASSIGNED)


class ReportHealthTests(unittest.TestCase):
    def setUp(self):
        self.rollout = FirmwareRollout(_artifact(), verifier=_verifier())
        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-001",
            ring=RolloutRing.CANARY, current_version="1.2.2",
        )
        self.rollout.deliver_to_ring(principal=_manager(), ring=RolloutRing.CANARY)

    def test_healthy_report_marks_healthy_and_keeps_new_version(self):
        state = self.rollout.report_health(device_id="synthetic-device-001", healthy=True)
        self.assertEqual(state.status, RolloutStatus.HEALTHY)
        self.assertEqual(state.current_version, "1.2.3")

    def test_unhealthy_report_auto_rolls_back_to_last_known_good(self):
        state = self.rollout.report_health(device_id="synthetic-device-001", healthy=False)
        self.assertEqual(state.status, RolloutStatus.ROLLED_BACK)
        self.assertEqual(state.current_version, "1.2.2")

    def test_report_health_for_unknown_device_raises(self):
        with self.assertRaises(DeviceNotInRolloutError):
            self.rollout.report_health(device_id="synthetic-device-unknown", healthy=True)

    def test_report_health_requires_in_progress_status(self):
        self.rollout.report_health(device_id="synthetic-device-001", healthy=True)
        with self.assertRaises(ValueError):
            self.rollout.report_health(device_id="synthetic-device-001", healthy=True)

    def test_report_health_does_not_require_a_principal(self):
        # Device-originated: no RBAC gate on this call at all.
        try:
            self.rollout.report_health(device_id="synthetic-device-001", healthy=True)
        except FirmwarePermissionError:
            self.fail("report_health must not require an RBAC principal")


class AdvanceRingTests(unittest.TestCase):
    def setUp(self):
        self.rollout = FirmwareRollout(_artifact(), verifier=_verifier())

    def test_cannot_advance_with_no_devices_in_ring(self):
        self.assertFalse(self.rollout.can_advance_ring())
        with self.assertRaises(RolloutBlockedError):
            self.rollout.advance_ring(principal=_manager())

    def test_cannot_advance_while_devices_are_still_in_progress(self):
        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-001",
            ring=RolloutRing.CANARY, current_version="1.2.2",
        )
        self.rollout.deliver_to_ring(principal=_manager(), ring=RolloutRing.CANARY)
        self.assertFalse(self.rollout.can_advance_ring())
        with self.assertRaises(RolloutBlockedError):
            self.rollout.advance_ring(principal=_manager())

    def test_cannot_advance_if_any_device_rolled_back(self):
        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-001",
            ring=RolloutRing.CANARY, current_version="1.2.2",
        )
        self.rollout.deliver_to_ring(principal=_manager(), ring=RolloutRing.CANARY)
        self.rollout.report_health(device_id="synthetic-device-001", healthy=False)
        self.assertFalse(self.rollout.can_advance_ring())
        with self.assertRaises(RolloutBlockedError):
            self.rollout.advance_ring(principal=_manager())

    def test_advances_once_all_ring_devices_are_healthy(self):
        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-001",
            ring=RolloutRing.CANARY, current_version="1.2.2",
        )
        self.rollout.deliver_to_ring(principal=_manager(), ring=RolloutRing.CANARY)
        self.rollout.report_health(device_id="synthetic-device-001", healthy=True)

        self.assertTrue(self.rollout.can_advance_ring())
        new_ring = self.rollout.advance_ring(principal=_manager())
        self.assertEqual(new_ring, RolloutRing.BROAD)
        self.assertEqual(self.rollout.current_ring(), RolloutRing.BROAD)

    def test_advance_ring_requires_firmware_manage_permission(self):
        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-001",
            ring=RolloutRing.CANARY, current_version="1.2.2",
        )
        self.rollout.deliver_to_ring(principal=_manager(), ring=RolloutRing.CANARY)
        self.rollout.report_health(device_id="synthetic-device-001", healthy=True)
        with self.assertRaises(FirmwarePermissionError):
            self.rollout.advance_ring(principal=_unauthorized_principal())

    def test_cannot_advance_past_the_final_ring(self):
        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-001",
            ring=RolloutRing.CANARY, current_version="1.2.2",
        )
        self.rollout.deliver_to_ring(principal=_manager(), ring=RolloutRing.CANARY)
        self.rollout.report_health(device_id="synthetic-device-001", healthy=True)
        self.rollout.advance_ring(principal=_manager())  # now at BROAD

        self.rollout.assign_device(
            principal=_manager(), device_id="synthetic-device-002",
            ring=RolloutRing.BROAD, current_version="1.2.2",
        )
        self.rollout.deliver_to_ring(principal=_manager(), ring=RolloutRing.BROAD)
        self.rollout.report_health(device_id="synthetic-device-002", healthy=True)

        with self.assertRaises(RolloutBlockedError):
            self.rollout.advance_ring(principal=_manager())


if __name__ == "__main__":
    unittest.main()
