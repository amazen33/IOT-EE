package com.iotee.platform.identity.adapter.out.persistence;

import com.iotee.platform.identity.port.out.RoleAssignmentRepository;
import com.iotee.platform.identity.rbac.Permission;
import com.iotee.platform.identity.rbac.RbacRegistry;
import com.iotee.platform.identity.rbac.Role;
import java.util.Set;

/**
 * In-memory implementation of {@link RoleAssignmentRepository} (ADR 0017
 * Decision 2, {@code adapter.out.<concern>}), backed by this service's
 * own {@link RbacRegistry}. A stand-in only: Track C step C4 replaces it
 * with a PostgreSQL adapter in this same package (ADR 0016/0017), and
 * nothing outside this package changes when it does.
 *
 * <p>No vendor persistence library is used here, so the ADR 0017
 * persistence-provider ban (Hibernate/EclipseLink/jOOQ only inside
 * {@code adapter.out.persistence}) is still vacuous.
 *
 * <p>Thread-safety: seed with {@link #assign} before the instance is
 * published (as {@link #withSyntheticSeed()} and the Spring
 * composition root do); after that it is read-only.
 */
public final class InMemoryRoleAssignmentRepository implements RoleAssignmentRepository {

    private final RbacRegistry registry = new RbacRegistry();

    /**
     * The walking skeleton's synthetic seed (development contract:
     * synthetic inputs only, never real identities) -- the same two
     * assignments {@code core.TenantPermissionsHandler} used to hard-code
     * in its constructor before step C1.
     */
    public static InMemoryRoleAssignmentRepository withSyntheticSeed() {
        return new InMemoryRoleAssignmentRepository()
                .assign("synthetic-subject-admin", Role.TENANT_ADMIN)
                .assign("synthetic-subject-viewer", Role.TENANT_VIEWER);
    }

    public InMemoryRoleAssignmentRepository assign(String subjectId, Role role) {
        registry.assign(subjectId, role);
        return this;
    }

    @Override
    public Set<Permission> permissionsFor(String subjectId) {
        return registry.permissionsFor(subjectId);
    }
}
