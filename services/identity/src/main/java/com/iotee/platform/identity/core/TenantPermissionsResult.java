package com.iotee.platform.identity.core;

import java.util.Set;

/**
 * Plain result type returned by {@link TenantPermissionsHandler}. No
 * Spring, no JSON annotations -- {@code services.identity.web}'s thin
 * controller maps this to whatever wire representation it needs
 * (currently {@code web.TenantPermissionsResponse}).
 */
public record TenantPermissionsResult(String tenantId, String subjectId, Set<String> permissions) {
}
