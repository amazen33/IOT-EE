package com.iotee.platform.identity.adapter.in.grpc.fixtures;

import com.iotee.platform.identity.adapter.in.rest.TenantPermissionsController;

/**
 * Deliberately violates "the two driving adapters never depend on each
 * other" from the gRPC side ({@code
 * IdentityHexagonalArchitectureRulesTest}). Test-fixture only.
 */
public class ViolatingGrpcAdapterUsesRestAdapter {
    private TenantPermissionsController sibling;

    public TenantPermissionsController sibling() {
        return sibling;
    }
}
