package com.iotee.platform.identity.rbac.policy;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One grant of permissions to one subject: the rule half of the policy.
 * Grants are data (versioned, audited by the owning store), never code.
 *
 * @param grantId      stable identifier, recorded in every decision that uses it
 * @param subjectId    who holds it
 * @param tenantId     the tenant it applies in; {@code null} means a platform grant
 *                     (for platform-scoped permissions such as an environment-admin channel)
 * @param permissions  permission keys granted; non-empty
 * @param deviceIds    device allowlist for device-scoped permissions; {@code null} = every
 *                     device in {@code tenantId} (still never another tenant's)
 * @param environments environment allowlist; {@code null} = not restricted by environment
 * @param notBefore    optional start
 * @param expiresAt    optional end (required by policy for privileged grants)
 * @param revokedAt    set when revoked; a revoked grant never permits anything from then on
 * @param grantedBy    subject that created the grant
 * @param approvalRef  just-in-time approval record, required for privileged grants
 */
public record Grant(
        String grantId,
        String subjectId,
        String tenantId,
        Set<String> permissions,
        Set<String> deviceIds,
        Set<String> environments,
        Instant notBefore,
        Instant expiresAt,
        Instant revokedAt,
        String grantedBy,
        String approvalRef) {

    public Grant {
        Objects.requireNonNull(grantId, "grantId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(grantedBy, "grantedBy");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("a grant must carry at least one permission");
        }
        deviceIds = deviceIds == null ? null : Set.copyOf(deviceIds);
        environments = environments == null ? null : Set.copyOf(environments);
        if (notBefore != null && expiresAt != null && !expiresAt.isAfter(notBefore)) {
            throw new IllegalArgumentException("expiresAt must be after notBefore");
        }
    }

    /** Not revoked, not expired and already started at {@code now}. */
    public boolean isActiveAt(Instant now) {
        return !isRevokedAt(now) && !isExpiredAt(now) && (notBefore == null || !now.isBefore(notBefore));
    }

    public boolean isRevokedAt(Instant now) {
        return revokedAt != null && !now.isBefore(revokedAt);
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** A copy of this grant revoked at {@code at}. Revocation is never undone; issue a new grant instead. */
    public Grant revokedAt(Instant at) {
        Objects.requireNonNull(at, "at");
        if (revokedAt != null) {
            return this;
        }
        return new Grant(grantId, subjectId, tenantId, permissions, deviceIds, environments,
                notBefore, expiresAt, at, grantedBy, approvalRef);
    }

    boolean coversDevice(String deviceId) {
        return deviceIds == null || (deviceId != null && deviceIds.contains(deviceId));
    }

    boolean coversEnvironment(String environment) {
        return environments == null || (environment != null && environments.contains(environment));
    }
}
