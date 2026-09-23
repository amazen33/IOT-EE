package com.iotee.platform.identity.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PermissionTest {

    @Test
    void keyMatchesTheDottedCatalogNamingConvention() {
        assertEquals("tenant.view", Permission.TENANT_READ.key());
        assertEquals("tenant.manage_roles", Permission.TENANT_MANAGE.key());
    }

    @Test
    void catalogContainsEveryDeclaredPermissionsKey() {
        var catalog = Permission.catalog();
        assertEquals(Permission.values().length, catalog.size());
        assertTrue(catalog.contains("tenant.view"));
        assertTrue(catalog.contains("tenant.manage_roles"));
    }
}
