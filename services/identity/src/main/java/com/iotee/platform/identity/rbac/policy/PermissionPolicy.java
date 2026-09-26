package com.iotee.platform.identity.rbac.policy;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned policy data for one permission: which attributes a request must
 * satisfy before any grant is even considered.
 *
 * @param permission          dotted permission key
 * @param audiences           client audiences allowed to exercise it
 * @param tiers               subject tiers allowed to hold and exercise it (grants never widen this)
 * @param minAuthStrength     minimum authentication strength (MFA)
 * @param maxAuthAge          step-up: authentication must be at most this old; {@code null} = no step-up
 * @param requiredEntitlement tenant entitlement required (feature gating); {@code null} = none
 * @param essential           telemetry, alarms, commands, security or incident reporting: can never
 *                            be gated by an entitlement, so disabling optional features cannot disable it
 * @param deviceScoped        the request must name a device, and the grant must cover it
 * @param platformScoped      a platform (environment) resource, never a tenant resource; granted only
 *                            by platform grants
 * @param privilegedGrant     grantable only by a system admin, with approval and a bounded expiry
 * @param maxGrantDuration    upper bound on a privileged grant's lifetime
 */
public record PermissionPolicy(
        String permission,
        Set<ClientAudience> audiences,
        Set<SubjectTier> tiers,
        AuthStrength minAuthStrength,
        Duration maxAuthAge,
        String requiredEntitlement,
        boolean essential,
        boolean deviceScoped,
        boolean platformScoped,
        boolean privilegedGrant,
        Duration maxGrantDuration) {

    public PermissionPolicy {
        Objects.requireNonNull(permission, "permission");
        audiences = Set.copyOf(Objects.requireNonNull(audiences, "audiences"));
        tiers = Set.copyOf(Objects.requireNonNull(tiers, "tiers"));
        Objects.requireNonNull(minAuthStrength, "minAuthStrength");
        if (audiences.isEmpty() || tiers.isEmpty()) {
            throw new IllegalArgumentException(permission + ": audiences and tiers must not be empty");
        }
        if (essential && requiredEntitlement != null) {
            throw new IllegalArgumentException(permission
                    + ": an essential capability cannot be gated by an entitlement (disabling an optional"
                    + " feature must never disable telemetry, alarms, commands, security or incident reporting)");
        }
        if (maxAuthAge != null && (maxAuthAge.isNegative() || maxAuthAge.isZero())) {
            throw new IllegalArgumentException(permission + ": maxAuthAge must be positive");
        }
        if (privilegedGrant) {
            if (maxGrantDuration == null || maxGrantDuration.isNegative() || maxGrantDuration.isZero()) {
                throw new IllegalArgumentException(permission + ": a privileged grant needs a positive maxGrantDuration");
            }
            if (!minAuthStrength.atLeast(AuthStrength.OTP)) {
                throw new IllegalArgumentException(permission + ": a privileged permission requires MFA");
            }
        }
    }

    public static Builder builder(String permission) {
        return new Builder(permission);
    }

    /** Readable construction of policy data; defaults are the most restrictive sensible values. */
    public static final class Builder {
        private final String permission;
        private Set<ClientAudience> audiences = EnumSet.noneOf(ClientAudience.class);
        private Set<SubjectTier> tiers = EnumSet.noneOf(SubjectTier.class);
        private AuthStrength minAuthStrength = AuthStrength.SINGLE_FACTOR;
        private Duration maxAuthAge;
        private String requiredEntitlement;
        private boolean essential;
        private boolean deviceScoped;
        private boolean platformScoped;
        private boolean privilegedGrant;
        private Duration maxGrantDuration;

        private Builder(String permission) {
            this.permission = permission;
        }

        public Builder audiences(ClientAudience first, ClientAudience... rest) {
            this.audiences = EnumSet.of(first, rest);
            return this;
        }

        public Builder tiers(SubjectTier first, SubjectTier... rest) {
            this.tiers = EnumSet.of(first, rest);
            return this;
        }

        public Builder minAuthStrength(AuthStrength strength) {
            this.minAuthStrength = strength;
            return this;
        }

        public Builder stepUpWithin(Duration maxAge) {
            this.maxAuthAge = maxAge;
            return this;
        }

        public Builder requiresEntitlement(String entitlement) {
            this.requiredEntitlement = entitlement;
            return this;
        }

        public Builder essential() {
            this.essential = true;
            return this;
        }

        public Builder deviceScoped() {
            this.deviceScoped = true;
            return this;
        }

        public Builder platformScoped() {
            this.platformScoped = true;
            return this;
        }

        public Builder privilegedGrant(Duration maxDuration) {
            this.privilegedGrant = true;
            this.maxGrantDuration = maxDuration;
            return this;
        }

        public PermissionPolicy build() {
            return new PermissionPolicy(permission, audiences, tiers, minAuthStrength, maxAuthAge,
                    requiredEntitlement, essential, deviceScoped, platformScoped, privilegedGrant, maxGrantDuration);
        }
    }
}
