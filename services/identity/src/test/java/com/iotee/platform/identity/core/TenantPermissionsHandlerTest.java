package com.iotee.platform.identity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Exercises {@link TenantPermissionsHandler} with a bare JUnit test --
 * no {@code @SpringBootTest}, no {@code MockMvc}, no Spring context at
 * all. This is the point of splitting {@code core} out from {@code web}
 * (see the Track B refactor's commit message): the handler logic must be
 * testable without spinning up Spring, and this test is the proof.
 */
class TenantPermissionsHandlerTest {

    private static final String SYNTHETIC_TENANT = "synthetic-tenant-acme-001";

    private final TenantPermissionsHandler handler = new TenantPermissionsHandler();

    @Test
    void adminSubjectReceivesBothPermissions() {
        TenantPermissionsResult result = handler.handle(SYNTHETIC_TENANT, "synthetic-subject-admin");

        assertEquals(SYNTHETIC_TENANT, result.tenantId());
        assertEquals("synthetic-subject-admin", result.subjectId());
        assertEquals(2, result.permissions().size());
        assertTrue(result.permissions().contains("TENANT_READ"));
        assertTrue(result.permissions().contains("TENANT_MANAGE"));
    }

    @Test
    void viewerSubjectReceivesOnlyReadPermission() {
        TenantPermissionsResult result = handler.handle(SYNTHETIC_TENANT, "synthetic-subject-viewer");

        assertEquals(1, result.permissions().size());
        assertTrue(result.permissions().contains("TENANT_READ"));
    }

    @Test
    void unassignedSubjectReceivesNoPermissions() {
        TenantPermissionsResult result = handler.handle(SYNTHETIC_TENANT, "synthetic-subject-unassigned");

        assertTrue(result.permissions().isEmpty());
    }

    @Test
    void nonSyntheticTenantIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> handler.handle("tenant-prod-4471", "synthetic-subject-admin"));
    }

    @Test
    void invalidTenantIdIsRejectedBeforeAnyRbacLookup() {
        // Documents the ordering: TenantId.of runs first, so an invalid
        // tenant id fails fast regardless of subjectId.
        assertThrows(IllegalArgumentException.class, () -> handler.handle("not-synthetic-at-all", "synthetic-subject-admin"));
    }
}
