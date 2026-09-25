package com.iotee.platform.identity.port.in;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link GetTenantPermissionsUseCase} (ADR 0017 Decision 2,
 * {@code port.in}). JDK types only, so every driving adapter can map it
 * onto its own wire format without touching a {@code domain} type.
 *
 * <p>{@code permissions} is an immutable list in ascending order, so REST
 * and gRPC responses for the same query are byte-for-byte deterministic
 * rather than dependent on set iteration order.
 */
public record TenantPermissionsView(String tenantId, String subjectId, List<String> permissions) {

    public TenantPermissionsView {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(subjectId, "subjectId");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }
}
