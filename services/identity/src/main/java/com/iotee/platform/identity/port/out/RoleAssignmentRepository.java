package com.iotee.platform.identity.port.out;

import com.iotee.platform.identity.rbac.Permission;
import java.util.Set;

/**
 * Outbound port: where the application reads a subject's RBAC grants
 * from (ADR 0017 Decision 2, {@code port.out}).
 *
 * <p>Today's only implementation is the in-memory, synthetic-seeded
 * {@code adapter.out.persistence.InMemoryRoleAssignmentRepository}. Step
 * C4 (Track C) replaces it with a PostgreSQL adapter under Row-Level
 * Security (ADR 0016) without changing this interface or any caller.
 */
public interface RoleAssignmentRepository {

    /** Permissions granted to {@code subjectId} by role; empty if the subject has no role. */
    Set<Permission> permissionsFor(String subjectId);
}
