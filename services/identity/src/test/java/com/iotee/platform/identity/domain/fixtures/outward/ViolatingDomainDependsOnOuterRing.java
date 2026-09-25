package com.iotee.platform.identity.domain.fixtures.outward;

import com.iotee.platform.identity.adapter.out.persistence.InMemoryRoleAssignmentRepository;
import com.iotee.platform.identity.port.in.GetTenantPermissionsUseCase;

/**
 * Deliberately violates "the domain has no outgoing dependency on ports,
 * the application layer, adapters, or config" ({@code
 * IdentityHexagonalArchitectureRulesTest}): it resides under
 * {@code domain} and references an inbound port and an outbound adapter.
 * Test-fixture only.
 */
public class ViolatingDomainDependsOnOuterRing {
    private GetTenantPermissionsUseCase port;
    private InMemoryRoleAssignmentRepository adapter;

    public Object[] outerRing() {
        return new Object[] {port, adapter};
    }
}
