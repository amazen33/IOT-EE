package com.iotee.platform.identity.port.in.fixtures;

import com.iotee.platform.identity.application.GetTenantPermissionsService;

/**
 * Deliberately violates "ports never depend on the application layer or
 * adapters" ({@code IdentityHexagonalArchitectureRulesTest}): it resides
 * under {@code port.in} and references the application service that
 * implements the port. Test-fixture only.
 */
public class ViolatingPortDependsOnApplication {
    private GetTenantPermissionsService implementation;

    public GetTenantPermissionsService implementation() {
        return implementation;
    }
}
