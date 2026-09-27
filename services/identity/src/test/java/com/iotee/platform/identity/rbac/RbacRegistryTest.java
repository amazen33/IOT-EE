package com.iotee.platform.identity.rbac;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RbacRegistryTest {

    private static final String TENANT_A = "synthetic-tenant-a";
    private static final String TENANT_B = "synthetic-tenant-b";

    @Test
    void aSubjectWithNoAssignmentHasNoPermissions() {
        RbacRegistry registry = new RbacRegistry();
        assertTrue(registry.permissionsFor(TENANT_A, "synthetic-subject-unassigned").isEmpty());
        assertFalse(registry.hasPermission(TENANT_A, "synthetic-subject-unassigned", Permission.TENANT_READ));
    }

    @Test
    void tenantViewerGrantsReadButNotManage() {
        RbacRegistry registry = new RbacRegistry();
        registry.assign(TENANT_A, "synthetic-subject-viewer", Role.TENANT_VIEWER);

        assertTrue(registry.hasPermission(TENANT_A, "synthetic-subject-viewer", Permission.TENANT_READ));
        assertFalse(registry.hasPermission(TENANT_A, "synthetic-subject-viewer", Permission.TENANT_MANAGE));
    }

    @Test
    void tenantAdminGrantsReadAndManage() {
        RbacRegistry registry = new RbacRegistry();
        registry.assign(TENANT_A, "synthetic-subject-admin", Role.TENANT_ADMIN);

        assertTrue(registry.hasPermission(TENANT_A, "synthetic-subject-admin", Permission.TENANT_READ));
        assertTrue(registry.hasPermission(TENANT_A, "synthetic-subject-admin", Permission.TENANT_MANAGE));
    }

    @Test
    void aRoleInOneTenantGrantsNothingInAnotherTenant() {
        RbacRegistry registry = new RbacRegistry();
        registry.assign(TENANT_A, "synthetic-subject-admin", Role.TENANT_ADMIN);

        assertTrue(registry.permissionsFor(TENANT_B, "synthetic-subject-admin").isEmpty());
        assertFalse(registry.hasPermission(TENANT_B, "synthetic-subject-admin", Permission.TENANT_MANAGE));
    }

    @Test
    void theSameSubjectMayHoldDifferentRolesInDifferentTenants() {
        RbacRegistry registry = new RbacRegistry();
        registry.assign(TENANT_A, "synthetic-subject-x", Role.TENANT_ADMIN);
        registry.assign(TENANT_B, "synthetic-subject-x", Role.TENANT_VIEWER);

        assertTrue(registry.hasPermission(TENANT_A, "synthetic-subject-x", Permission.TENANT_MANAGE));
        assertFalse(registry.hasPermission(TENANT_B, "synthetic-subject-x", Permission.TENANT_MANAGE));
    }

    @Test
    void reassigningASubjectReplacesItsPreviousRoleInThatTenant() {
        RbacRegistry registry = new RbacRegistry();
        registry.assign(TENANT_A, "synthetic-subject-x", Role.TENANT_ADMIN);
        registry.assign(TENANT_A, "synthetic-subject-x", Role.TENANT_VIEWER);

        assertFalse(registry.hasPermission(TENANT_A, "synthetic-subject-x", Permission.TENANT_MANAGE));
        assertTrue(registry.hasPermission(TENANT_A, "synthetic-subject-x", Permission.TENANT_READ));
    }

    @Test
    void revokingRemovesTheRoleInThatTenantOnly() {
        RbacRegistry registry = new RbacRegistry();
        registry.assign(TENANT_A, "synthetic-subject-x", Role.TENANT_ADMIN);
        registry.assign(TENANT_B, "synthetic-subject-x", Role.TENANT_ADMIN);

        registry.revoke(TENANT_A, "synthetic-subject-x");

        assertTrue(registry.permissionsFor(TENANT_A, "synthetic-subject-x").isEmpty());
        assertTrue(registry.hasPermission(TENANT_B, "synthetic-subject-x", Permission.TENANT_MANAGE));
    }

    @Test
    void assignRejectsNullTenantSubjectOrRole() {
        RbacRegistry registry = new RbacRegistry();
        assertThrows(NullPointerException.class, () -> registry.assign(null, "synthetic-subject-x", Role.TENANT_VIEWER));
        assertThrows(NullPointerException.class, () -> registry.assign(TENANT_A, null, Role.TENANT_VIEWER));
        assertThrows(NullPointerException.class, () -> registry.assign(TENANT_A, "synthetic-subject-x", null));
    }
}
