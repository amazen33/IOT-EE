"""alarm-command bounded context: the operator/device command domain
skeleton (requirements-addendum.md, "Tenant operators and secure device
property updates").

This models desired-vs-reported command state, authorization against an
operator's allowlisted device scope and writable properties, TTL/expiry,
and idempotent dispatch. It does not talk to a device, a broker, or any
transport (that is M3's authenticated device delivery) — this is the
domain core the delivery mechanism will sit on top of.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
import uuid

from domain_core.devices import Device
from domain_core.rbac import Principal, PrincipalKind
from domain_core.units import UtcTimestamp

# Writable properties an operator may be allowlisted for. "firmware.*" is
# kept as a distinct, higher-privilege family per the addendum's firmware
# entitlement section (checked with the .firmware permission, not the
# plain device.command.dispatch permission).
WRITABLE_PROPERTY_CATALOG = frozenset(
    {
        "target_level_percent",
        "reporting_interval_seconds",
        "valve_open",
        "firmware.rollout_ring",
    }
)

_FIRMWARE_PROPERTIES = frozenset(p for p in WRITABLE_PROPERTY_CATALOG if p.startswith("firmware."))


class CommandStatus(str, Enum):
    PENDING = "pending"
    ACKNOWLEDGED = "acknowledged"
    FAILED = "failed"
    EXPIRED = "expired"


class CommandAuthorizationError(PermissionError):
    pass


class InvalidCommandTransition(ValueError):
    pass


@dataclass(frozen=True)
class OperatorDeviceScope:
    """What one operator principal is allowed to command. ``device_ids``
    of ``None`` means "all devices in the operator's own tenant" (still
    tenant-bounded, never cross-tenant) -- an explicit set is the allowlist
    the addendum calls for when narrower scope is required."""

    principal_id: str
    tenant_id: str
    device_ids: frozenset[str] | None
    writable_properties: frozenset[str]
    expires_at: UtcTimestamp | None = None

    def __post_init__(self) -> None:
        unknown = set(self.writable_properties) - WRITABLE_PROPERTY_CATALOG
        if unknown:
            raise ValueError(f"Unknown writable propert(y/ies): {sorted(unknown)}")

    def is_expired(self, *, now: UtcTimestamp) -> bool:
        return self.expires_at is not None and self.expires_at <= now

    def covers_device(self, device_id_value: str) -> bool:
        return self.device_ids is None or device_id_value in self.device_ids


@dataclass(frozen=True)
class CommandRequest:
    command_id: str
    tenant_id: str
    device_id_value: str
    property_name: str
    desired_value: object
    issued_by: str
    issued_at: UtcTimestamp
    ttl_seconds: int
    status: CommandStatus = CommandStatus.PENDING
    idempotency_key: str | None = None

    def expires_at(self) -> UtcTimestamp:
        return self.issued_at.add_seconds(self.ttl_seconds)

    def is_expired(self, *, now: UtcTimestamp) -> bool:
        return self.expires_at() <= now


def authorize_command(
    *, principal: Principal, scope: OperatorDeviceScope, device: Device, property_name: str, now: UtcTimestamp
) -> None:
    """Raises CommandAuthorizationError on any failure; returns None on
    success. Every check is a separate, testable failure mode."""
    if principal.principal_id != scope.principal_id:
        raise CommandAuthorizationError("Scope does not belong to this principal")
    if principal.kind is PrincipalKind.SYSTEM_ADMIN:
        raise CommandAuthorizationError("System-admin tokens must not dispatch tenant device commands directly")
    if device.device_id.tenant_id != principal.tenant_id or device.device_id.tenant_id != scope.tenant_id:
        raise CommandAuthorizationError("Cross-tenant device access is not permitted")
    if property_name not in WRITABLE_PROPERTY_CATALOG:
        raise CommandAuthorizationError(f"'{property_name}' is not a recognized writable property")
    required_permission = "device.command.dispatch.firmware" if property_name in _FIRMWARE_PROPERTIES else "device.command.dispatch"
    if not principal.has_permission(required_permission):
        raise CommandAuthorizationError(f"Principal lacks '{required_permission}'")
    if scope.is_expired(now=now):
        raise CommandAuthorizationError("Operator scope has expired")
    if not scope.covers_device(device.device_id.value):
        raise CommandAuthorizationError("Device is outside the operator's allowlisted scope")
    if property_name not in scope.writable_properties:
        raise CommandAuthorizationError(f"'{property_name}' is outside the operator's writable-property allowlist")


class CommandStore:
    """In-memory command store. Idempotent dispatch: a repeated call with
    the same (device, property, idempotency_key) while a prior command is
    still pending returns the existing command rather than creating a
    duplicate -- this is the domain-core half of "prevent replay"; actual
    device-side replay protection is a transport concern (M3)."""

    def __init__(self) -> None:
        self._commands: dict[str, CommandRequest] = {}
        self._idempotency_index: dict[tuple[str, str, str], str] = {}

    def dispatch(
        self,
        *,
        principal: Principal,
        scope: OperatorDeviceScope,
        device: Device,
        property_name: str,
        desired_value: object,
        ttl_seconds: int,
        now: UtcTimestamp,
        idempotency_key: str | None = None,
    ) -> CommandRequest:
        authorize_command(principal=principal, scope=scope, device=device, property_name=property_name, now=now)
        if ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be positive")

        if idempotency_key is not None:
            index_key = (device.device_id.value, property_name, idempotency_key)
            existing_id = self._idempotency_index.get(index_key)
            if existing_id is not None:
                existing = self._commands[existing_id]
                if not existing.is_expired(now=now) and existing.status == CommandStatus.PENDING:
                    return existing

        command = CommandRequest(
            command_id=str(uuid.uuid4()),
            tenant_id=device.device_id.tenant_id,
            device_id_value=device.device_id.value,
            property_name=property_name,
            desired_value=desired_value,
            issued_by=principal.principal_id,
            issued_at=now,
            ttl_seconds=ttl_seconds,
            idempotency_key=idempotency_key,
        )
        self._commands[command.command_id] = command
        if idempotency_key is not None:
            self._idempotency_index[(device.device_id.value, property_name, idempotency_key)] = command.command_id
        return command

    def get(self, command_id: str) -> CommandRequest:
        return self._commands[command_id]

    def refresh_status(self, command_id: str, *, now: UtcTimestamp) -> CommandRequest:
        """Lazily promote a stale PENDING command to EXPIRED. Bounds how
        long an offline device's queue can appear pending forever."""
        command = self._commands[command_id]
        if command.status == CommandStatus.PENDING and command.is_expired(now=now):
            command = _with_status(command, CommandStatus.EXPIRED)
            self._commands[command_id] = command
        return command

    def acknowledge(self, command_id: str, *, now: UtcTimestamp) -> CommandRequest:
        command = self.refresh_status(command_id, now=now)
        if command.status != CommandStatus.PENDING:
            raise InvalidCommandTransition(f"Cannot acknowledge a command in status '{command.status.value}'")
        command = _with_status(command, CommandStatus.ACKNOWLEDGED)
        self._commands[command_id] = command
        return command

    def fail(self, command_id: str, *, now: UtcTimestamp) -> CommandRequest:
        command = self.refresh_status(command_id, now=now)
        if command.status != CommandStatus.PENDING:
            raise InvalidCommandTransition(f"Cannot fail a command in status '{command.status.value}'")
        command = _with_status(command, CommandStatus.FAILED)
        self._commands[command_id] = command
        return command


def _with_status(command: CommandRequest, status: CommandStatus) -> CommandRequest:
    return CommandRequest(
        command_id=command.command_id,
        tenant_id=command.tenant_id,
        device_id_value=command.device_id_value,
        property_name=command.property_name,
        desired_value=command.desired_value,
        issued_by=command.issued_by,
        issued_at=command.issued_at,
        ttl_seconds=command.ttl_seconds,
        status=status,
        idempotency_key=command.idempotency_key,
    )
