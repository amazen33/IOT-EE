package com.iotee.platform.identity.port.in;

/**
 * Inbound port for the tenant-permissions use case (ADR 0017 Decision 2,
 * {@code port.in}). Implemented by the application layer
 * ({@code application.GetTenantPermissionsService}); called by every
 * driving adapter. Framework-free: no Spring, servlet, or gRPC type
 * appears in this interface or its parameter and return types.
 */
public interface GetTenantPermissionsUseCase {

    /**
     * @return the validated tenant id, the subject id, and the granted
     *     permission names in ascending order.
     * @throws InvalidQueryException if {@code query} fails validation;
     *     each driving adapter maps this to its own transport's
     *     "invalid argument" status (HTTP 400, gRPC INVALID_ARGUMENT).
     */
    TenantPermissionsView getTenantPermissions(GetTenantPermissionsQuery query);
}
