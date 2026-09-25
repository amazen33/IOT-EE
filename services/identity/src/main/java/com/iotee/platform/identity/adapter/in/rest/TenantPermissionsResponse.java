package com.iotee.platform.identity.adapter.in.rest;

import com.iotee.platform.identity.port.in.TenantPermissionsView;
import java.util.List;

/**
 * JSON response body for {@link TenantPermissionsController#getPermissions}.
 * Same JSON shape as before step C1; {@code permissions} is now an
 * ordered array (ascending), matching the gRPC response's order.
 */
public record TenantPermissionsResponse(String tenantId, String subjectId, List<String> permissions) {

    static TenantPermissionsResponse from(TenantPermissionsView view) {
        return new TenantPermissionsResponse(view.tenantId(), view.subjectId(), view.permissions());
    }
}
