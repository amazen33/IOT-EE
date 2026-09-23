package com.iotee.platform.identity.core;

import com.iotee.platform.identity.domain.TenantId;
import com.iotee.platform.identity.rbac.AbacContext;
import com.iotee.platform.identity.rbac.AbacDecision;
import com.iotee.platform.identity.rbac.Permission;
import com.iotee.platform.identity.rbac.RbacRegistry;
import com.iotee.platform.identity.rbac.Role;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The walking skeleton's one piece of real endpoint logic (ADR 0012
 * Decision 6), framework-free: plain method, plain parameters, plain
 * return type ({@link TenantPermissionsResult}) -- no Spring imports
 * anywhere in this class, so it is testable with a bare JUnit test and
 * no Spring context (see {@code TenantPermissionsHandlerTest}).
 * {@code services.identity.web.TenantPermissionsController} is the thin
 * Spring adapter that calls this class and maps its result onto an HTTP
 * response; this class knows nothing about HTTP, Spring, or servlets.
 *
 * <p>Real backing: validates {@code tenantId} via {@link TenantId#of},
 * looks up {@code subjectId}'s role via {@link RbacRegistry} (in-memory,
 * seeded here with two synthetic assignments so this walking skeleton
 * returns something meaningful without a real persistence layer -- ADR
 * 0012 non-goals), and consults {@link AbacContext#alwaysPermit()} as
 * the ABAC hook this handler is expected to call once a real ABAC engine
 * exists. No SSO/JWT, no real tenant persistence, no full RBAC/ABAC --
 * see this module's README.md for the complete non-goals list.
 */
public class TenantPermissionsHandler {

    private final RbacRegistry rbacRegistry = new RbacRegistry();
    private final AbacContext abacContext = AbacContext.alwaysPermit();

    public TenantPermissionsHandler() {
        // Synthetic seed data only (development contract: synthetic inputs,
        // never real identities) -- exists so this walking-skeleton handler
        // has something non-empty to return without a persistence layer.
        rbacRegistry.assign("synthetic-subject-admin", Role.TENANT_ADMIN);
        rbacRegistry.assign("synthetic-subject-viewer", Role.TENANT_VIEWER);
    }

    /**
     * @param rawTenantId the tenant id exactly as received (e.g. an HTTP
     *     path variable) -- validated here via {@link TenantId#of}.
     * @param subjectId the subject whose permissions are being resolved.
     * @return the tenant id (in its validated, canonical form), the
     *     subject id, and the set of permission names granted to
     *     {@code subjectId} for that tenant.
     * @throws IllegalArgumentException if {@code rawTenantId} fails
     *     {@link TenantId#of}'s validation -- the caller (the web layer)
     *     decides how to map that to a transport-level error.
     */
    public TenantPermissionsResult handle(String rawTenantId, String subjectId) {
        TenantId validatedTenantId = TenantId.of(rawTenantId);

        Set<Permission> rbacPermissions = rbacRegistry.permissionsFor(subjectId);

        Set<Permission> granted = EnumSet.noneOf(Permission.class);
        for (Permission permission : rbacPermissions) {
            AbacDecision decision = abacContext.evaluate(subjectId, permission, validatedTenantId.value());
            if (decision == AbacDecision.PERMIT) {
                granted.add(permission);
            }
        }

        Set<String> permissionNames = granted.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
        return new TenantPermissionsResult(validatedTenantId.value(), subjectId, permissionNames);
    }
}
