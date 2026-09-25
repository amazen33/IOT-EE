package com.iotee.platform.identity.adapter.in.rest.fixtures;

import com.iotee.platform.identity.adapter.in.grpc.TenantPermissionsGrpcService;

/**
 * Deliberately violates "the two driving adapters never depend on each
 * other" ({@code IdentityHexagonalArchitectureRulesTest}): it resides
 * under {@code adapter.in.rest} and holds a reference to the gRPC
 * adapter. Test-fixture only.
 */
public class ViolatingRestAdapterUsesGrpcAdapter {
    private TenantPermissionsGrpcService sibling;

    public TenantPermissionsGrpcService sibling() {
        return sibling;
    }
}
