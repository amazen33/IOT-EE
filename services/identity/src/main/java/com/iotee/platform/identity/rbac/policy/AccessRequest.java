package com.iotee.platform.identity.rbac.policy;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the decision point needs, supplied by the calling service:
 * the authenticated subject, the requested permission and resource, the
 * resource tenant's current entitlements, the subject's grants and the
 * evaluation time. Entitlements are tenant facts set by system admins; they
 * are not permissions, release flags or quotas.
 */
public record AccessRequest(
        Subject subject,
        String permission,
        ResourceRef resource,
        Set<String> tenantEntitlements,
        Collection<Grant> subjectGrants,
        Instant now) {

    public AccessRequest {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(resource, "resource");
        tenantEntitlements = Set.copyOf(Objects.requireNonNull(tenantEntitlements, "tenantEntitlements"));
        subjectGrants = List.copyOf(Objects.requireNonNull(subjectGrants, "subjectGrants"));
        Objects.requireNonNull(now, "now");
    }
}
