package com.iotee.platform.identity.adapter.out.persistence;

import com.iotee.platform.identity.domain.TenantId;
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
 * composition root do); after that it is read-only in production. {@link #revoke}
 * exists for tests that need to observe revocation reaching
 * {@code application.GetTenantPermissionsService}'s RBAC-bridged
 * authorization end to end (see
 * {@code GetTenantPermissionsServiceTest.revokedRoleAssignmentDeniesAccessOnTheNextRequest}) --
 * like {@link RbacRegistry} itself, neither method is synchronized beyond
 * what a single-threaded test needs.
 */
public final class InMemoryRoleAssignmentRepository implements RoleAssignmentRepository {

    private final RbacRegistry registry = new RbacRegistry();

    /** The one synthetic tenant the walking-skeleton seed assigns roles in. */
    public static final String SYNTHETIC_SEED_TENANT = "synthetic-tenant-acme-001";

    /**
     * The walking skeleton's synthetic seed (development contract:
     * synthetic inputs only, never real identities): the same two
     * subjects as before step C1, now assigned in
     * {@link #SYNTHETIC_SEED_TENANT} only. They hold nothing in any other
     * tenant.
     */
    public static InMemoryRoleAssignmentRepository withSyntheticSeed() {
        return new InMemoryRoleAssignmentRepository()
                .assign(SYNTHETIC_SEED_TENANT, "synthetic-subject-admin", Role.TENANT_ADMIN)
                .assign(SYNTHETIC_SEED_TENANT, "synthetic-subject-viewer", Role.TENANT_VIEWER);
    }

    public InMemoryRoleAssignmentRepository assign(String tenantId, String subjectId, Role role) {
        registry.assign(tenantId, subjectId, role);
        return this;
    }

    /** Removes {@code subjectId}'s role assignment in {@code tenantId}; a no-op if none was assigned. */
    public InMemoryRoleAssignmentRepository revoke(String tenantId, String subjectId) {
        registry.revoke(tenantId, subjectId);
        return this;
    }

    @Override
    public Set<Permission> permissionsFor(TenantId tenantId, String subjectId) {
        return registry.permissionsFor(tenantId.value(), subjectId);
    }
}
