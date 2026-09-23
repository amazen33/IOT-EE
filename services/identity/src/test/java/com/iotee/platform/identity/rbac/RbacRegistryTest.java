package com.iotee.platform.identity.rbac;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RbacRegistryTest {

    @Test
    void aSubjectWithNoAssignmentHasNoPermissions() {
        RbacRegistry registry = new RbacRegistry();
        assertTrue(registry.permissionsFor("synthetic-subject-unassigned").isEmpty());
        assertFalse(registry.hasPermission("synthetic-subject-unassigned", Permission.TENANT_READ));
    }

    @Test
    void tenantViewerGrantsReadButNotManage() {
        RbacRegistry registry = new RbacRegistry();
        registry.assign("synthetic-subject-viewer", Role.TENANT_VIEWER);

        assertTrue(registry.hasPermission("synthetic-subject-viewer", Permission.TENANT_READ));
        assertFalse(registry.hasPermission("synthetic-subject-viewer", Permission.TENANT_MANAGE));
    }

    @Test
    void tenantAdminGrantsReadAndManage() {
        RbacRegistry registry = new RbacRegistry();
        registry.assign("synthetic-subject-admin", Role.TENANT_ADMIN);

        assertTrue(registry.hasPermission("synthetic-subject-admin", Permission.TENANT_READ));
        assertTrue(registry.hasPermission("synthetic-subject-admin", Permission.TENANT_MANAGE));
    }

    @Test
    void reassigningASubjectReplacesItsPreviousRole() {
        RbacRegistry registry = new RbacRegistry();
        registry.assign("synthetic-subject-x", Role.TENANT_ADMIN);
        registry.assign("synthetic-subject-x", Role.TENANT_VIEWER);

        assertFalse(registry.hasPermission("synthetic-subject-x", Permission.TENANT_MANAGE));
        assertTrue(registry.hasPermission("synthetic-subject-x", Permission.TENANT_READ));
    }

    @Test
    void assignRejectsNullSubject() {
        RbacRegistry registry = new RbacRegistry();
        assertThrows(NullPointerException.class, () -> registry.assign(null, Role.TENANT_VIEWER));
    }

    @Test
    void assignRejectsNullRole() {
        RbacRegistry registry = new RbacRegistry();
        assertThrows(NullPointerException.class, () -> registry.assign("synthetic-subject-x", null));
    }
}
