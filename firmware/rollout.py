"""firmware-management bounded context: staged canary/broad rollout with
automatic rollback (M5, requirements-addendum.md's "rollback constraints").

Decision (docs/adr/0006-m5-firmware.md): devices are assigned to rollout
rings (canary(s) then broad); a ring only advances once every device
assigned to it has reported a healthy post-update check; a device that
reports unhealthy automatically reverts to its last-known-good version
rather than staying on the failed update or requiring a separate manual
rollback command. This models the *policy* only -- no real update
delivery/download/flashing transport exists here (see
``firmware.delivery`` for the delivery contract, contract-only per
docs/firmware.md).

A ``FirmwareRollout`` cannot be constructed for an artifact whose
provenance does not verify -- ``verify_firmware_provenance`` runs in
``__init__``, so a tampered or unsigned artifact is a structural
impossibility to roll out, not a policy note.

Two distinct permissions gate this module, both already in M2's
``domain_core.rbac.PERMISSION_CATALOG``:
``firmware.manage`` gates rollout-plan-level operations (assigning
devices, advancing a ring) here; the pre-existing
``device.command.dispatch.firmware`` (used by
``domain_core.commands.authorize_command``) gates dispatching a
firmware-related command to one specific device and is not
re-implemented or duplicated by this module.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from domain_core.rbac import Principal
from firmware.provenance import FirmwareArtifact, verify_firmware_provenance
from firmware.signing import SignatureVerifier

class RolloutRing(str, Enum):
    CANARY = "canary"
    BROAD = "broad"


DEFAULT_RINGS: tuple[RolloutRing, ...] = (RolloutRing.CANARY, RolloutRing.BROAD)


class RolloutStatus(str, Enum):
    ASSIGNED = "assigned"
    IN_PROGRESS = "in_progress"
    HEALTHY = "healthy"
    ROLLED_BACK = "rolled_back"


class FirmwarePermissionError(PermissionError):
    pass


class RolloutBlockedError(RuntimeError):
    pass


class DeviceNotInRolloutError(KeyError):
    pass


@dataclass(frozen=True)
class DeviceFirmwareState:
    device_id: str
    ring: RolloutRing
    status: RolloutStatus
    current_version: str
    previous_version: str | None = None


def _require_firmware_manage(principal: Principal) -> None:
    if not principal.has_permission("firmware.manage"):
        raise FirmwarePermissionError("Principal lacks 'firmware.manage'")


class FirmwareRollout:
    """In-memory rollout-plan state machine for one verified
    ``FirmwareArtifact`` across an ordered sequence of rings."""

    def __init__(
        self,
        artifact: FirmwareArtifact,
        *,
        verifier: SignatureVerifier,
        rings: tuple[RolloutRing, ...] = DEFAULT_RINGS,
    ) -> None:
        if not rings:
            raise ValueError("rings must be non-empty")
        verify_firmware_provenance(artifact, verifier=verifier)
        self._artifact = artifact
        self._rings = rings
        self._ring_index = 0
        self._devices: dict[str, DeviceFirmwareState] = {}

    @property
    def artifact(self) -> FirmwareArtifact:
        return self._artifact

    def current_ring(self) -> RolloutRing:
        return self._rings[self._ring_index]

    def assign_device(
        self, *, principal: Principal, device_id: str, ring: RolloutRing, current_version: str
    ) -> DeviceFirmwareState:
        _require_firmware_manage(principal)
        if ring not in self._rings:
            raise ValueError(f"Ring '{ring.value}' is not part of this rollout")
        state = DeviceFirmwareState(
            device_id=device_id, ring=ring, status=RolloutStatus.ASSIGNED,
            current_version=current_version, previous_version=None,
        )
        self._devices[device_id] = state
        return state

    def deliver_to_ring(self, *, principal: Principal, ring: RolloutRing) -> list[DeviceFirmwareState]:
        """Marks every ASSIGNED device in ``ring`` as IN_PROGRESS,
        recording its pre-update version as ``previous_version`` (the
        last-known-good version a failed health check reverts to). Does
        not itself deliver anything over any transport -- see
        ``firmware.delivery`` for the (contract-only) delivery route."""
        _require_firmware_manage(principal)
        delivered = []
        for device_id, state in list(self._devices.items()):
            if state.ring == ring and state.status == RolloutStatus.ASSIGNED:
                new_state = DeviceFirmwareState(
                    device_id=device_id, ring=ring, status=RolloutStatus.IN_PROGRESS,
                    current_version=self._artifact.version, previous_version=state.current_version,
                )
                self._devices[device_id] = new_state
                delivered.append(new_state)
        return delivered

    def report_health(self, *, device_id: str, healthy: bool) -> DeviceFirmwareState:
        """Device-originated: no RBAC gate, since a health report comes
        from the device/telemetry pipeline, not an operator action. An
        unhealthy report auto-reverts ``current_version`` to the device's
        last-known-good version rather than requiring a separate manual
        rollback command."""
        state = self._devices.get(device_id)
        if state is None:
            raise DeviceNotInRolloutError(device_id)
        if state.status != RolloutStatus.IN_PROGRESS:
            raise ValueError(f"Device '{device_id}' is not awaiting a health report (status={state.status.value})")

        if healthy:
            new_state = DeviceFirmwareState(
                device_id=device_id, ring=state.ring, status=RolloutStatus.HEALTHY,
                current_version=state.current_version, previous_version=state.previous_version,
            )
        else:
            if state.previous_version is None:
                raise ValueError(f"Device '{device_id}' has no last-known-good version to roll back to")
            new_state = DeviceFirmwareState(
                device_id=device_id, ring=state.ring, status=RolloutStatus.ROLLED_BACK,
                current_version=state.previous_version, previous_version=state.previous_version,
            )
        self._devices[device_id] = new_state
        return new_state

    def can_advance_ring(self) -> bool:
        ring_devices = self.devices_in_ring(self.current_ring())
        return bool(ring_devices) and all(state.status == RolloutStatus.HEALTHY for state in ring_devices)

    def advance_ring(self, *, principal: Principal) -> RolloutRing:
        _require_firmware_manage(principal)
        if self._ring_index >= len(self._rings) - 1:
            raise RolloutBlockedError("Rollout is already at its final ring")
        if not self.can_advance_ring():
            raise RolloutBlockedError("Current ring is not fully healthy; cannot advance")
        self._ring_index += 1
        return self.current_ring()

    def state_for(self, device_id: str) -> DeviceFirmwareState | None:
        return self._devices.get(device_id)

    def devices_in_ring(self, ring: RolloutRing) -> list[DeviceFirmwareState]:
        return [state for state in self._devices.values() if state.ring == ring]
