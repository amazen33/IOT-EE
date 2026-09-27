package com.iotee.platform.identity.rbac;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory role assignment lookup, scoped per tenant (ADR 0016 Decision 6:
 * a Tier 2 principal may act only in its own tenant). This is the RBAC half
 * of the "RBAC and ABAC" access-control primitive; the attribute half is
 * {@link com.iotee.platform.identity.rbac.policy.PolicyDecisionPoint}.
 *
 * <p>Assignments are keyed by {@code (tenantId, subjectId)}, never by
 * subject alone: a role held in one tenant grants nothing in any other
 * tenant. The previous subject-only key let a tenant admin in one tenant
 * read as an admin of every tenant; {@code RbacRegistryTest} and
 * {@code GetTenantPermissionsServiceTest} now pin the cross-tenant case.
 *
 * <p>Thread-safety: intentionally minimal and NOT thread-safe beyond what a
 * single-threaded walking-skeleton test needs. Track C step C4 replaces the
 * in-memory store with a persisted one under PostgreSQL RLS (ADR 0016).
 */
public final class RbacRegistry {

    private record Key(String tenantId, String subjectId) {
    }

    private final Map<Key, Role> assignments = new HashMap<>();

    /** Assigns {@code role} to {@code subjectId} within {@code tenantId} only. */
    public void assign(String tenantId, String subjectId, Role role) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(role, "role");
        assignments.put(new Key(tenantId, subjectId), role);
    }

    /** Removes {@code subjectId}'s role in {@code tenantId}; a no-op if none was assigned. */
    public void revoke(String tenantId, String subjectId) {
        assignments.remove(new Key(Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(subjectId, "subjectId")));
    }

    /**
     * Permissions granted to {@code subjectId} by its role in {@code tenantId};
     * empty if the subject has no role in that tenant (including when it has
     * a role in a different tenant).
     */
    public Set<Permission> permissionsFor(String tenantId, String subjectId) {
        Role role = assignments.get(new Key(tenantId, subjectId));
        return role == null ? EnumSet.noneOf(Permission.class) : role.permissions();
    }

    /** Whether {@code subjectId} holds {@code permission} in {@code tenantId}, by RBAC alone. */
    public boolean hasPermission(String tenantId, String subjectId, Permission permission) {
        return permissionsFor(tenantId, subjectId).contains(permission);
    }
}
