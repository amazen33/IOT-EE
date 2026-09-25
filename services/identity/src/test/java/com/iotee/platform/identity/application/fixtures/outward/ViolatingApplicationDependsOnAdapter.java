package com.iotee.platform.identity.application.fixtures.outward;

import com.iotee.platform.identity.adapter.out.persistence.InMemoryRoleAssignmentRepository;

/**
 * Deliberately violates "the application layer reaches driven adapters
 * only through port.out" ({@code IdentityHexagonalArchitectureRulesTest}):
 * it resides under {@code application} and names a concrete outbound
 * adapter. Test-fixture only.
 */
public class ViolatingApplicationDependsOnAdapter {
    private final InMemoryRoleAssignmentRepository concreteAdapter = InMemoryRoleAssignmentRepository.withSyntheticSeed();

    public InMemoryRoleAssignmentRepository concreteAdapter() {
        return concreteAdapter;
    }
}
