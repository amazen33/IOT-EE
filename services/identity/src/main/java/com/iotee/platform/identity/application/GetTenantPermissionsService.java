package com.iotee.platform.identity.application;

import com.iotee.platform.identity.domain.TenantId;
import com.iotee.platform.identity.domain.TenantIdValidationException;
import com.iotee.platform.identity.port.in.GetTenantPermissionsQuery;
import com.iotee.platform.identity.port.in.GetTenantPermissionsUseCase;
import com.iotee.platform.identity.port.in.InvalidQueryException;
import com.iotee.platform.identity.port.in.TenantPermissionsView;
import com.iotee.platform.identity.port.out.RoleAssignmentRepository;
import com.iotee.platform.identity.rbac.AbacContext;
import com.iotee.platform.identity.rbac.AbacDecision;
import com.iotee.platform.identity.rbac.Permission;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Application service implementing {@link GetTenantPermissionsUseCase}
 * (ADR 0017 Decision 2, {@code application}). Formerly
 * {@code core.TenantPermissionsHandler}; same logic, now behind an
 * inbound port and fed by an outbound port instead of constructing its
 * own collaborators.
 *
 * <p>Framework-free: plain constructor injection, no Spring annotations
 * (Spring wiring lives only in {@code config}, per ADR 0017 Decision 2),
 * so it is tested with a bare JUnit test and no Spring context.
 *
 * <p>Validation happens here, once, for every transport:
 * <ul>
 *   <li>{@code tenantId} must pass {@link TenantId#of}; a failure is
 *       rethrown as {@link InvalidQueryException} with the domain
 *       exception as its cause;</li>
 *   <li>{@code subjectId} must not be blank. This is the one intentional
 *       behavior change in step C1: proto3 cannot tell "absent" from
 *       "empty", so without this rule an empty gRPC {@code subject_id}
 *       would succeed with no permissions while REST rejects a missing
 *       parameter -- the transports would disagree. Blank is now invalid
 *       on both.</li>
 * </ul>
 */
public final class GetTenantPermissionsService implements GetTenantPermissionsUseCase {

    private final RoleAssignmentRepository roleAssignments;
    private final AbacContext abacContext;

    public GetTenantPermissionsService(RoleAssignmentRepository roleAssignments, AbacContext abacContext) {
        this.roleAssignments = Objects.requireNonNull(roleAssignments, "roleAssignments");
        this.abacContext = Objects.requireNonNull(abacContext, "abacContext");
    }

    @Override
    public TenantPermissionsView getTenantPermissions(GetTenantPermissionsQuery query) {
        Objects.requireNonNull(query, "query");

        TenantId tenantId;
        try {
            tenantId = TenantId.of(query.tenantId());
        } catch (TenantIdValidationException e) {
            throw new InvalidQueryException("tenantId failed validation", e);
        }
        String subjectId = query.subjectId();
        if (subjectId.isBlank()) {
            throw new InvalidQueryException("subjectId must not be blank", null);
        }

        Set<Permission> rbacPermissions = roleAssignments.permissionsFor(subjectId);

        List<String> granted = rbacPermissions.stream()
                .filter(permission ->
                        abacContext.evaluate(subjectId, permission, tenantId.value()) == AbacDecision.PERMIT)
                .map(Enum::name)
                .sorted()
                .toList();

        return new TenantPermissionsView(tenantId.value(), subjectId, granted);
    }
}
