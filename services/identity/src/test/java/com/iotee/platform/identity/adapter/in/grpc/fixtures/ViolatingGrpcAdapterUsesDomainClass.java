package com.iotee.platform.identity.adapter.in.grpc.fixtures;

import com.iotee.platform.identity.domain.TenantId;

/**
 * Deliberately violates "a driving adapter depends on port.in, never on
 * the domain" for the gRPC adapter ({@code
 * IdentityHexagonalArchitectureRulesTest}). Test-fixture only.
 */
public class ViolatingGrpcAdapterUsesDomainClass {
    public String bypassThePort(String rawTenantId) {
        return TenantId.of(rawTenantId).value();
    }
}
