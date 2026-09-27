package com.iotee.platform.identity.rbac.policy;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A proposed grant, with the facts needed to decide whether the grantor may
 * make it.
 *
 * @param grantor         the authenticated subject creating the grant
 * @param grantorGrants   the grantor's own grants (delegation cannot exceed them)
 * @param proposed        the grant to create; {@code grantedBy} must be the grantor
 * @param granteeTier     the grantee's tier (a grant never changes it)
 * @param granteeTenantId the grantee's tenant, or null for a Tier 1 grantee
 * @param now             evaluation time
 */
public record GrantRequest(
        Subject grantor,
        Collection<Grant> grantorGrants,
        Grant proposed,
        SubjectTier granteeTier,
        String granteeTenantId,
        Instant now) {

    public GrantRequest {
        Objects.requireNonNull(grantor, "grantor");
        grantorGrants = List.copyOf(Objects.requireNonNull(grantorGrants, "grantorGrants"));
        Objects.requireNonNull(proposed, "proposed");
        Objects.requireNonNull(granteeTier, "granteeTier");
        Objects.requireNonNull(now, "now");
    }
}
