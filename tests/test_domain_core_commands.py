import unittest

from domain_core.commands import (
    CommandAuthorizationError,
    CommandStatus,
    CommandStore,
    InvalidCommandTransition,
    OperatorDeviceScope,
    authorize_command,
)
from domain_core.devices import Device, DeviceId
from domain_core.rbac import Principal, PrincipalKind
from domain_core.units import UtcTimestamp


def _operator(tenant_id="synthetic-tenant-a", permissions=frozenset({"device.command.dispatch"})):
    return Principal(principal_id="synthetic-operator-1", kind=PrincipalKind.TENANT_OPERATOR, tenant_id=tenant_id, permissions=permissions)


def _device(tenant_id="synthetic-tenant-a"):
    return Device(device_id=DeviceId.generate(tenant_id), tank_id="synthetic-tank-1", label="Tank 1 sensor")


def _scope(principal, device_ids=None, properties=frozenset({"target_level_percent"}), expires_at=None):
    return OperatorDeviceScope(
        principal_id=principal.principal_id,
        tenant_id=principal.tenant_id,
        device_ids=device_ids,
        writable_properties=properties,
        expires_at=expires_at,
    )


class AuthorizeCommandTests(unittest.TestCase):
    def setUp(self):
        self.now = UtcTimestamp.now()
        self.operator = _operator()
        self.device = _device()

    def test_happy_path_is_authorized(self):
        scope = _scope(self.operator)
        authorize_command(principal=self.operator, scope=scope, device=self.device, property_name="target_level_percent", now=self.now)

    def test_cross_tenant_device_access_rejected(self):
        other_tenant_device = _device(tenant_id="synthetic-tenant-b")
        scope = _scope(self.operator)
        with self.assertRaises(CommandAuthorizationError):
            authorize_command(principal=self.operator, scope=scope, device=other_tenant_device, property_name="target_level_percent", now=self.now)

    def test_unassigned_device_rejected(self):
        other_device = _device()
        scope = _scope(self.operator, device_ids=frozenset({self.device.device_id.value}))
        with self.assertRaises(CommandAuthorizationError):
            authorize_command(principal=self.operator, scope=scope, device=other_device, property_name="target_level_percent", now=self.now)

    def test_forbidden_property_rejected(self):
        scope = _scope(self.operator, properties=frozenset({"valve_open"}))
        with self.assertRaises(CommandAuthorizationError):
            authorize_command(principal=self.operator, scope=scope, device=self.device, property_name="target_level_percent", now=self.now)

    def test_unrecognized_property_rejected(self):
        scope = _scope(self.operator, properties=frozenset({"target_level_percent"}))
        with self.assertRaises(CommandAuthorizationError):
            authorize_command(principal=self.operator, scope=scope, device=self.device, property_name="not_a_real_property", now=self.now)

    def test_missing_dispatch_permission_rejected(self):
        operator = _operator(permissions=frozenset({"device.view"}))
        scope = _scope(operator)
        with self.assertRaises(CommandAuthorizationError):
            authorize_command(principal=operator, scope=scope, device=self.device, property_name="target_level_percent", now=self.now)

    def test_firmware_property_requires_firmware_permission(self):
        scope = _scope(self.operator, properties=frozenset({"firmware.rollout_ring"}))
        with self.assertRaises(CommandAuthorizationError):
            authorize_command(principal=self.operator, scope=scope, device=self.device, property_name="firmware.rollout_ring", now=self.now)

        entitled_operator = _operator(permissions=frozenset({"device.command.dispatch.firmware"}))
        entitled_scope = _scope(entitled_operator, properties=frozenset({"firmware.rollout_ring"}))
        authorize_command(principal=entitled_operator, scope=entitled_scope, device=_device(), property_name="firmware.rollout_ring", now=self.now)

    def test_expired_scope_rejected(self):
        scope = _scope(self.operator, expires_at=self.now)
        later = self.now.add_seconds(1)
        with self.assertRaises(CommandAuthorizationError):
            authorize_command(principal=self.operator, scope=scope, device=self.device, property_name="target_level_percent", now=later)

    def test_system_admin_cannot_dispatch_directly(self):
        admin = Principal(principal_id="synthetic-sysadmin-1", kind=PrincipalKind.SYSTEM_ADMIN, tenant_id=None)
        scope = OperatorDeviceScope(
            principal_id="synthetic-sysadmin-1", tenant_id="synthetic-tenant-a",
            device_ids=None, writable_properties=frozenset({"target_level_percent"}),
        )
        with self.assertRaises(CommandAuthorizationError):
            authorize_command(principal=admin, scope=scope, device=self.device, property_name="target_level_percent", now=self.now)

    def test_scope_belonging_to_a_different_principal_rejected(self):
        other_operator = _operator()
        mismatched_scope = OperatorDeviceScope(
            principal_id="someone-else", tenant_id="synthetic-tenant-a",
            device_ids=None, writable_properties=frozenset({"target_level_percent"}),
        )
        with self.assertRaises(CommandAuthorizationError):
            authorize_command(principal=other_operator, scope=mismatched_scope, device=self.device, property_name="target_level_percent", now=self.now)


class CommandStoreTests(unittest.TestCase):
    def setUp(self):
        self.now = UtcTimestamp.now()
        self.operator = _operator()
        self.device = _device()
        self.scope = _scope(self.operator)
        self.store = CommandStore()

    def test_dispatch_creates_pending_command(self):
        command = self.store.dispatch(
            principal=self.operator, scope=self.scope, device=self.device,
            property_name="target_level_percent", desired_value=42, ttl_seconds=60, now=self.now,
        )
        self.assertEqual(command.status, CommandStatus.PENDING)

    def test_dispatch_enforces_authorization(self):
        forbidden_scope = _scope(self.operator, properties=frozenset({"valve_open"}))
        with self.assertRaises(CommandAuthorizationError):
            self.store.dispatch(
                principal=self.operator, scope=forbidden_scope, device=self.device,
                property_name="target_level_percent", desired_value=1, ttl_seconds=60, now=self.now,
            )

    def test_duplicate_idempotency_key_returns_same_command(self):
        first = self.store.dispatch(
            principal=self.operator, scope=self.scope, device=self.device,
            property_name="target_level_percent", desired_value=42, ttl_seconds=60, now=self.now,
            idempotency_key="synthetic-retry-1",
        )
        second = self.store.dispatch(
            principal=self.operator, scope=self.scope, device=self.device,
            property_name="target_level_percent", desired_value=42, ttl_seconds=60, now=self.now.add_seconds(1),
            idempotency_key="synthetic-retry-1",
        )
        self.assertEqual(first.command_id, second.command_id)

    def test_stale_command_expires_and_is_not_reused(self):
        first = self.store.dispatch(
            principal=self.operator, scope=self.scope, device=self.device,
            property_name="target_level_percent", desired_value=42, ttl_seconds=10, now=self.now,
            idempotency_key="synthetic-retry-2",
        )
        much_later = self.now.add_seconds(3600)
        refreshed = self.store.refresh_status(first.command_id, now=much_later)
        self.assertEqual(refreshed.status, CommandStatus.EXPIRED)

        second = self.store.dispatch(
            principal=self.operator, scope=self.scope, device=self.device,
            property_name="target_level_percent", desired_value=99, ttl_seconds=10, now=much_later,
            idempotency_key="synthetic-retry-2",
        )
        self.assertNotEqual(first.command_id, second.command_id)

    def test_acknowledge_transitions_pending_to_acknowledged(self):
        command = self.store.dispatch(
            principal=self.operator, scope=self.scope, device=self.device,
            property_name="target_level_percent", desired_value=42, ttl_seconds=60, now=self.now,
        )
        acknowledged = self.store.acknowledge(command.command_id, now=self.now.add_seconds(1))
        self.assertEqual(acknowledged.status, CommandStatus.ACKNOWLEDGED)

    def test_cannot_acknowledge_twice(self):
        command = self.store.dispatch(
            principal=self.operator, scope=self.scope, device=self.device,
            property_name="target_level_percent", desired_value=42, ttl_seconds=60, now=self.now,
        )
        self.store.acknowledge(command.command_id, now=self.now.add_seconds(1))
        with self.assertRaises(InvalidCommandTransition):
            self.store.acknowledge(command.command_id, now=self.now.add_seconds(2))

    def test_cannot_acknowledge_expired_command(self):
        command = self.store.dispatch(
            principal=self.operator, scope=self.scope, device=self.device,
            property_name="target_level_percent", desired_value=42, ttl_seconds=1, now=self.now,
        )
        with self.assertRaises(InvalidCommandTransition):
            self.store.acknowledge(command.command_id, now=self.now.add_seconds(3600))

    def test_fail_transitions_pending_to_failed(self):
        command = self.store.dispatch(
            principal=self.operator, scope=self.scope, device=self.device,
            property_name="target_level_percent", desired_value=42, ttl_seconds=60, now=self.now,
        )
        failed = self.store.fail(command.command_id, now=self.now.add_seconds(1))
        self.assertEqual(failed.status, CommandStatus.FAILED)

    def test_non_positive_ttl_rejected(self):
        with self.assertRaises(ValueError):
            self.store.dispatch(
                principal=self.operator, scope=self.scope, device=self.device,
                property_name="target_level_percent", desired_value=42, ttl_seconds=0, now=self.now,
            )


if __name__ == "__main__":
    unittest.main()
