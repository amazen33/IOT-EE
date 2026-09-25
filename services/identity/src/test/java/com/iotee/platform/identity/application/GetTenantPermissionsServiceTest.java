package com.iotee.platform.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iotee.platform.identity.adapter.out.persistence.InMemoryRoleAssignmentRepository;
import com.iotee.platform.identity.domain.TenantIdValidationException;
import com.iotee.platform.identity.port.in.GetTenantPermissionsQuery;
import com.iotee.platform.identity.port.in.InvalidQueryException;
import com.iotee.platform.identity.port.in.TenantPermissionsView;
import com.iotee.platform.identity.port.out.RoleAssignmentRepository;
import com.iotee.platform.identity.rbac.AbacContext;
import com.iotee.platform.identity.rbac.AbacDecision;
import com.iotee.platform.identity.rbac.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link GetTenantPermissionsService} with a bare JUnit test --
 * no Spring context. Formerly {@code core.TenantPermissionsHandlerTest};
 * the first four cases are the same behavior, now reached through the
 * inbound port with the in-memory outbound adapter.
 */
class GetTenantPermissionsServiceTest {

    private static final String SYNTHETIC_TENANT = "synthetic-tenant-acme-001";

    private final GetTenantPermissionsService service = new GetTenantPermissionsService(
            InMemoryRoleAssignmentRepository.withSyntheticSeed(), AbacContext.alwaysPermit());

    private TenantPermissionsView query(String tenantId, String subjectId) {
        return service.getTenantPermissions(new GetTenantPermissionsQuery(tenantId, subjectId));
    }

    @Test
    void adminSubjectReceivesBothPermissionsInAscendingOrder() {
        TenantPermissionsView view = query(SYNTHETIC_TENANT, "synthetic-subject-admin");

        assertEquals(SYNTHETIC_TENANT, view.tenantId());
        assertEquals("synthetic-subject-admin", view.subjectId());
        assertEquals(List.of("TENANT_MANAGE", "TENANT_READ"), view.permissions());
    }

    @Test
    void viewerSubjectReceivesOnlyReadPermission() {
        assertEquals(List.of("TENANT_READ"), query(SYNTHETIC_TENANT, "synthetic-subject-viewer").permissions());
    }

    @Test
    void unassignedSubjectReceivesNoPermissions() {
        assertTrue(query(SYNTHETIC_TENANT, "synthetic-subject-unassigned").permissions().isEmpty());
    }

    @Test
    void nonSyntheticTenantIdIsRejectedAsAnInvalidQueryWrappingTheDomainFailure() {
        InvalidQueryException ex = assertThrows(InvalidQueryException.class,
                () -> query("tenant-prod-4471", "synthetic-subject-admin"));

        assertInstanceOf(TenantIdValidationException.class, ex.getCause(),
                "the domain's own validation failure must be kept as the cause for operator logging");
    }

    @Test
    void invalidTenantIdIsRejectedBeforeAnyRbacLookup() {
        List<String> lookups = new ArrayList<>();
        RoleAssignmentRepository recording = subjectId -> {
            lookups.add(subjectId);
            return Set.of();
        };
        GetTenantPermissionsService recordingService =
                new GetTenantPermissionsService(recording, AbacContext.alwaysPermit());

        assertThrows(InvalidQueryException.class, () -> recordingService.getTenantPermissions(
                new GetTenantPermissionsQuery("not-synthetic-at-all", "synthetic-subject-admin")));
        assertTrue(lookups.isEmpty(), "tenant validation must run before the outbound port is called");
    }

    @Test
    void blankSubjectIdIsRejected() {
        // Step C1's one intentional behavior change: blank is invalid on every
        // transport (proto3 cannot distinguish an absent subject_id from "").
        assertThrows(InvalidQueryException.class, () -> query(SYNTHETIC_TENANT, ""));
        assertThrows(InvalidQueryException.class, () -> query(SYNTHETIC_TENANT, "   "));
    }

    @Test
    void abacDenialRemovesAnRbacGrant() {
        AbacContext denyManage = (subjectId, permission, resourceId) ->
                permission == Permission.TENANT_MANAGE ? AbacDecision.DENY : AbacDecision.PERMIT;
        GetTenantPermissionsService abacService = new GetTenantPermissionsService(
                InMemoryRoleAssignmentRepository.withSyntheticSeed(), denyManage);

        TenantPermissionsView view = abacService.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-admin"));

        assertEquals(List.of("TENANT_READ"), view.permissions());
    }

    @Test
    void abacIsEvaluatedAgainstTheValidatedTenantId() {
        List<String> evaluatedResources = new ArrayList<>();
        AbacContext recording = (subjectId, permission, resourceId) -> {
            evaluatedResources.add(resourceId);
            return AbacDecision.PERMIT;
        };
        GetTenantPermissionsService abacService = new GetTenantPermissionsService(
                InMemoryRoleAssignmentRepository.withSyntheticSeed(), recording);

        abacService.getTenantPermissions(new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-viewer"));

        assertEquals(List.of(SYNTHETIC_TENANT), evaluatedResources);
    }
}
