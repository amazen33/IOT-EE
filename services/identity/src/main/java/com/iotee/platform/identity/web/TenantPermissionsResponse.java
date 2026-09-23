package com.iotee.platform.identity.web;

import java.util.Set;

/** Response body for {@link TenantPermissionsController#getPermissions}. */
public record TenantPermissionsResponse(String tenantId, String subjectId, Set<String> permissions) {
}
