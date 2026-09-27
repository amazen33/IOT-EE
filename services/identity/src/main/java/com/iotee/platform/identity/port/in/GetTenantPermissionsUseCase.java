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
     * @throws InvalidQueryException if {@code query}'s {@code tenantId} or
     *     {@code subjectId} fails validation; each driving adapter maps
     *     this to its own transport's "invalid argument" status (HTTP 400,
     *     gRPC INVALID_ARGUMENT).
     * @throws AuthenticationFailedException if {@code query.bearerToken()}
     *     is absent, forged, expired, or presents the wrong issuer,
     *     audience, client or tier; mapped to HTTP 401 / gRPC
     *     UNAUTHENTICATED. A gateway-supplied identity header is never
     *     consulted and can never substitute for this token (ADR 0016
     *     Decision 4).
     * @throws AuthorizationDeniedException if the authenticated caller is
     *     not permitted to read {@code query.tenantId()}'s permission
     *     assignments (deny-by-default policy, cross-tenant for a Tier 2
     *     caller, or no active grant); mapped to HTTP 403 / gRPC
     *     PERMISSION_DENIED.
     */
    TenantPermissionsView getTenantPermissions(GetTenantPermissionsQuery query);
}
