import unittest

from domain_core.rbac import (
    PERMISSION_CATALOG,
    CrossTenantDelegationError,
    Principal,
    PrincipalKind,
    Role,
    SelfEscalationError,
    assign_role,
    create_role,
)


class RbacTests(unittest.TestCase):
    def setUp(self):
        self.system_admin = Principal(
            principal_id="synthetic-sysadmin-1", kind=PrincipalKind.SYSTEM_ADMIN, tenant_id=None
        )
        self.tenant_admin = Principal(
            principal_id="synthetic-admin-1",
            kind=PrincipalKind.TENANT_ADMIN,
            tenant_id="synthetic-tenant-a",
            permissions=frozenset({"tenant.manage_roles", "device.command.dispatch", "device.view"}),
        )
        self.operator = Principal(
            principal_id="synthetic-operator-1",
            kind=PrincipalKind.TENANT_OPERATOR,
            tenant_id="synthetic-tenant-a",
            permissions=frozenset({"device.view"}),
        )

    def test_role_rejects_unknown_permissions(self):
        with self.assertRaises(ValueError):
            Role(role_id="r1", tenant_id="synthetic-tenant-a", name="Bad", permissions=frozenset({"not.a.permission"}))

    def test_system_admin_has_every_catalog_permission(self):
        for permission in PERMISSION_CATALOG:
            self.assertTrue(self.system_admin.has_permission(permission))

    def test_tenant_admin_can_create_role_within_their_own_authority(self):
        role = create_role(
            assigner=self.tenant_admin,
            role_id="synthetic-role-viewer",
            tenant_id="synthetic-tenant-a",
            name="Viewer",
            permissions=frozenset({"device.view"}),
        )
        self.assertEqual(role.permissions, frozenset({"device.view"}))

    def test_self_escalation_is_rejected(self):
        with self.assertRaises(SelfEscalationError):
            create_role(
                assigner=self.tenant_admin,
                role_id="synthetic-role-firmware",
                tenant_id="synthetic-tenant-a",
                name="Firmware Admin",
                permissions=frozenset({"firmware.manage"}),
            )

    def test_cross_tenant_delegation_is_rejected(self):
        with self.assertRaises(CrossTenantDelegationError):
            create_role(
                assigner=self.tenant_admin,
                role_id="synthetic-role-other-tenant",
                tenant_id="synthetic-tenant-b",
                name="Sneaky",
                permissions=frozenset({"device.view"}),
            )

    def test_operator_without_manage_roles_cannot_create_roles(self):
        operator_with_matching_perms = Principal(
            principal_id="synthetic-operator-2",
            kind=PrincipalKind.TENANT_OPERATOR,
            tenant_id="synthetic-tenant-a",
            permissions=frozenset({"device.view", "device.command.dispatch"}),
        )
        with self.assertRaises(ValueError):
            create_role(
                assigner=operator_with_matching_perms,
                role_id="synthetic-role-x",
                tenant_id="synthetic-tenant-a",
                name="X",
                permissions=frozenset({"device.view"}),
            )

    def test_system_admin_may_create_role_in_any_tenant(self):
        role = create_role(
            assigner=self.system_admin,
            role_id="synthetic-role-any",
            tenant_id="synthetic-tenant-z",
            name="Any",
            permissions=frozenset({"firmware.manage"}),
        )
        self.assertEqual(role.tenant_id, "synthetic-tenant-z")

    def test_assign_role_enforces_same_rules_as_create_role(self):
        role = create_role(
            assigner=self.tenant_admin,
            role_id="synthetic-role-viewer-2",
            tenant_id="synthetic-tenant-a",
            name="Viewer2",
            permissions=frozenset({"device.view"}),
        )
        assignment = assign_role(assigner=self.tenant_admin, assignee_principal_id="synthetic-operator-1", role=role)
        self.assertEqual(assignment.role_id, "synthetic-role-viewer-2")

        other_tenant_role = Role(
            role_id="synthetic-role-elsewhere", tenant_id="synthetic-tenant-b", name="Elsewhere", permissions=frozenset()
        )
        with self.assertRaises(CrossTenantDelegationError):
            assign_role(assigner=self.tenant_admin, assignee_principal_id="x", role=other_tenant_role)

    def test_system_admin_principal_cannot_carry_tenant_id(self):
        with self.assertRaises(ValueError):
            Principal(principal_id="bad", kind=PrincipalKind.SYSTEM_ADMIN, tenant_id="synthetic-tenant-a")

    def test_tenant_scoped_principal_requires_tenant_id(self):
        with self.assertRaises(ValueError):
            Principal(principal_id="bad", kind=PrincipalKind.TENANT_ADMIN, tenant_id=None)

    def test_has_permission_rejects_unknown_permission_string(self):
        with self.assertRaises(ValueError):
            self.operator.has_permission("not.a.real.permission")


if __name__ == "__main__":
    unittest.main()
