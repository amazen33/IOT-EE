package com.iotee.platform.identity.adapter.in.rest.fixtures;

import com.iotee.platform.identity.domain.TenantId;

/**
 * Deliberately violates "a driving adapter depends on port.in, never on
 * the domain" ({@code IdentityHexagonalArchitectureRulesTest}): it
 * resides under {@code adapter.in.rest} and validates a tenant id with
 * the domain type directly, bypassing the inbound port. Test-fixture only.
 */
public class ViolatingRestAdapterUsesDomainClass {
    public String bypassThePort(String rawTenantId) {
        return TenantId.of(rawTenantId).value();
    }
}
