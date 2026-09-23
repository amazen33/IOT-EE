package com.iotee.platform.identity.web;

import com.iotee.platform.identity.core.TenantPermissionsHandler;
import com.iotee.platform.identity.core.TenantPermissionsResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The walking skeleton's one endpoint (ADR 0012 Decision 6):
 * {@code GET /tenants/{tenantId}/permissions?subjectId=...}.
 *
 * <p>Deliberately thin: this class's only job is translating an HTTP
 * request into a call to {@link TenantPermissionsHandler#handle} and its
 * {@link TenantPermissionsResult} back into an HTTP response. All real
 * logic (tenant id validation, RBAC lookup, ABAC evaluation) lives in
 * {@code core.TenantPermissionsHandler}, which has no Spring dependency
 * and is tested without one. {@code TenantId.of}'s
 * {@link IllegalArgumentException} for an invalid tenant id propagates
 * out of {@link TenantPermissionsHandler#handle} and is mapped to a 400
 * by Spring's default exception handling -- this controller does not
 * catch it itself.
 */
@RestController
public class TenantPermissionsController {

    private final TenantPermissionsHandler handler = new TenantPermissionsHandler();

    @GetMapping("/tenants/{tenantId}/permissions")
    public ResponseEntity<TenantPermissionsResponse> getPermissions(
            @PathVariable("tenantId") String tenantId,
            @RequestParam("subjectId") String subjectId) {

        TenantPermissionsResult result = handler.handle(tenantId, subjectId);

        return ResponseEntity.ok(
                new TenantPermissionsResponse(result.tenantId(), result.subjectId(), result.permissions()));
    }
}
