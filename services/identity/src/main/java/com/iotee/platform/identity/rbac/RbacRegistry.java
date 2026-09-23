package com.iotee.platform.identity.rbac;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory, per-tenant role assignment lookup (ADR 0012 non-goals: no
 * persistence beyond in-memory yet, no SSO/JWT, no full RBAC/ABAC). This
 * is the RBAC half of the "RBAC and ABAC" access-control primitive; see
 * {@link AbacContext} for the ABAC hook stub.
 *
 * <p>Thread-safety: this class is intentionally minimal and NOT
 * thread-safe beyond what a single-threaded walking-skeleton test needs.
 * A real implementation backing a running service replaces this with a
 * persisted, concurrency-safe store -- this class exists to give
 * services/identity's walking skeleton something real to call.
 */
public final class RbacRegistry {

    private final java.util.Map<String, Role> assignmentsBySubject = new java.util.HashMap<>();

    /** Assigns {@code role} to {@code subjectId} within the caller's tenant scope. */
    public void assign(String subjectId, Role role) {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(role, "role");
        assignmentsBySubject.put(subjectId, role);
    }

    /** Returns the permissions granted to {@code subjectId}, or an empty set if it has no role assigned. */
    public Set<Permission> permissionsFor(String subjectId) {
        Role role = assignmentsBySubject.get(subjectId);
        return role == null ? EnumSet.noneOf(Permission.class) : role.permissions();
    }

    /** Returns whether {@code subjectId} holds {@code permission}, considering RBAC only (no ABAC). */
    public boolean hasPermission(String subjectId, Permission permission) {
        return permissionsFor(subjectId).contains(permission);
    }
}
