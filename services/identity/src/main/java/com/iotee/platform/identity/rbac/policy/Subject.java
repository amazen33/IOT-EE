package com.iotee.platform.identity.rbac.policy;

import java.time.Instant;
import java.util.Objects;

/**
 * An authenticated caller, as mapped from verified token claims.
 *
 * @param subjectId        stable subject identifier
 * @param tier             one of the four roles; never granted, only authenticated
 * @param tenantId         the subject's tenant; required for Tier 2, absent (null) for Tier 1
 * @param audience         the client/audience the token was issued for
 * @param authStrength     strongest factor used in this session
 * @param authenticatedAt  when that authentication happened (null when the issuer omitted auth_time)
 */
public record Subject(
        String subjectId,
        SubjectTier tier,
        String tenantId,
        ClientAudience audience,
        AuthStrength authStrength,
        Instant authenticatedAt) {

    public Subject {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(authStrength, "authStrength");
        if (subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId must not be blank");
        }
        if (tier.isTenantTier() && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("a tenant-tier subject requires a tenantId");
        }
        if (!tier.isTenantTier() && tenantId != null) {
            throw new IllegalArgumentException("a platform-tier subject must not carry a tenantId");
        }
    }
}
