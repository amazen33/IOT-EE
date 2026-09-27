package com.iotee.platform.identity.rbac.policy;

import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a grantor may create a proposed grant: delegation and
 * privileged (environment-admin channel) grants.
 *
 * <ul>
 *   <li>No self-grants, for any tier.</li>
 *   <li>Operators (Tier 1 or Tier 2) never grant.</li>
 *   <li>A grant can never give a permission to a tier that policy does not
 *       allow to hold it: an environment-admin channel grant does not make
 *       anyone a system admin.</li>
 *   <li>Privileged grants: system admins only, with a just-in-time approval
 *       reference, an expiry within the policy maximum, and a grantor who
 *       satisfies the permission's MFA and step-up requirement.</li>
 *   <li>Tenant admins: only in their own tenant, only to subjects of that
 *       tenant, only with the delegation permission, and never beyond an
 *       active grant they themselves hold (permission, devices, environments
 *       and expiry).</li>
 * </ul>
 *
 * <p>Not decided here: whether the approval reference points at a real,
 * approved, unexpired request by a second person. That needs the approval
 * workflow and store, which do not exist yet.
 */
public final class GrantAuthority {

    private final PolicySet policies;
    private final String delegationPermission;
    private final String privilegedGrantPermission;

    /**
     * @param delegationPermission the permission a tenant admin must hold in its tenant to delegate
     * @param privilegedGrantPermission the platform permission governing system-admin grant actions
     */
    public GrantAuthority(PolicySet policies, String delegationPermission, String privilegedGrantPermission) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.delegationPermission = Objects.requireNonNull(delegationPermission, "delegationPermission");
        this.privilegedGrantPermission = Objects.requireNonNull(privilegedGrantPermission, "privilegedGrantPermission");
    }

    public PolicyDecision decide(GrantRequest request) {
        Objects.requireNonNull(request, "request");
        int v = policies.version();
        Subject grantor = request.grantor();
        Grant proposed = request.proposed();

        if (!proposed.grantedBy().equals(grantor.subjectId())) {
            return PolicyDecision.deny(DecisionReason.DENY_GRANTOR_MISMATCH, proposed.grantedBy(), v);
        }
        if (proposed.subjectId().equals(grantor.subjectId())) {
            return PolicyDecision.deny(DecisionReason.DENY_SELF_GRANT, proposed.grantId(), v);
        }
        if (grantor.tier() == SubjectTier.PRODUCT_OPERATOR || grantor.tier() == SubjectTier.TENANT_OPERATOR) {
            return PolicyDecision.deny(DecisionReason.DENY_GRANTOR_TIER, grantor.tier().name(), v);
        }
        if (request.granteeTier().isTenantTier() != (request.granteeTenantId() != null)) {
            return PolicyDecision.deny(DecisionReason.DENY_SCOPE_MISMATCH, "grantee tier and tenant disagree", v);
        }
        boolean needsDelegation = false;
        boolean needsPrivilegedGrant = false;
        for (String permission : proposed.permissions()) {
            Optional<PermissionPolicy> found = policies.policyFor(permission);
            if (found.isEmpty()) {
                return PolicyDecision.deny(DecisionReason.DENY_UNKNOWN_PERMISSION, permission, v);
            }
            needsPrivilegedGrant |= found.get().privilegedGrant();
            needsDelegation |= !found.get().privilegedGrant();
        }
        if (needsDelegation) {
            Optional<PermissionPolicy> delegationPolicy = policies.policyFor(delegationPermission);
            if (delegationPolicy.isEmpty()) {
                return PolicyDecision.deny(DecisionReason.DENY_UNKNOWN_PERMISSION, delegationPermission, v);
            }
            PolicyDecision delegationAuth = checkGrantorAuthentication(grantor, delegationPolicy.get(), request.now(), v);
            if (delegationAuth != null) {
                return delegationAuth;
            }
        }
        if (needsPrivilegedGrant) {
            if (grantor.tier() != SubjectTier.PRODUCT_ADMIN) {
                return PolicyDecision.deny(DecisionReason.DENY_PRIVILEGED_GRANTOR, proposed.grantId(), v);
            }
            Optional<PermissionPolicy> grantPolicy = policies.policyFor(privilegedGrantPermission);
            if (grantPolicy.isEmpty()) {
                return PolicyDecision.deny(DecisionReason.DENY_UNKNOWN_PERMISSION, privilegedGrantPermission, v);
            }
            PolicyDecision privilegedAuth = checkGrantorAuthentication(grantor, grantPolicy.get(), request.now(), v);
            if (privilegedAuth != null) {
                return privilegedAuth;
            }
        }
        for (String permission : proposed.permissions()) {
            PermissionPolicy found = policies.policyFor(permission).orElseThrow();
            PolicyDecision perPermission = checkPermission(request, found, v);
            if (perPermission != null) {
                return perPermission;
            }
        }
        return new PolicyDecision(DecisionReason.PERMIT_GRANT_VALID, proposed.grantId(), v);
    }

    private static PolicyDecision checkGrantorAuthentication(Subject grantor, PermissionPolicy policy,
            java.time.Instant now, int v) {
        if (!policy.tiers().contains(grantor.tier()) || !policy.audiences().contains(grantor.audience())) {
            return PolicyDecision.deny(DecisionReason.DENY_GRANTOR_TIER, grantor.tier().name(), v);
        }
        if (!grantor.authStrength().atLeast(policy.minAuthStrength())) {
            return PolicyDecision.deny(DecisionReason.DENY_AUTH_STRENGTH, policy.minAuthStrength().name(), v);
        }
        if (policy.maxAuthAge() != null && (grantor.authenticatedAt() == null
                || grantor.authenticatedAt().isAfter(now)
                || grantor.authenticatedAt().plus(policy.maxAuthAge()).isBefore(now))) {
            return PolicyDecision.deny(DecisionReason.DENY_STEP_UP_REQUIRED, policy.maxAuthAge().toString(), v);
        }
        return null;
    }

    /** Null when this permission is acceptable in the proposed grant, otherwise the denial. */
    private PolicyDecision checkPermission(GrantRequest request, PermissionPolicy policy, int v) {
        Subject grantor = request.grantor();
        Grant proposed = request.proposed();
        String permission = policy.permission();

        if (!policy.tiers().contains(request.granteeTier())) {
            return PolicyDecision.deny(DecisionReason.DENY_TIER, permission + " for " + request.granteeTier(), v);
        }
        if (policy.platformScoped() != (proposed.tenantId() == null)) {
            return PolicyDecision.deny(DecisionReason.DENY_SCOPE_MISMATCH, permission, v);
        }
        if (!policy.platformScoped() && request.granteeTier().isTenantTier()
                && !proposed.tenantId().equals(request.granteeTenantId())) {
            return PolicyDecision.deny(DecisionReason.DENY_CROSS_TENANT, proposed.tenantId(), v);
        }

        if (policy.privilegedGrant()) {
            if (grantor.tier() != SubjectTier.PRODUCT_ADMIN) {
                return PolicyDecision.deny(DecisionReason.DENY_PRIVILEGED_GRANTOR, permission, v);
            }
            if (proposed.approvalRef() == null || proposed.approvalRef().isBlank()) {
                return PolicyDecision.deny(DecisionReason.DENY_APPROVAL_REQUIRED, permission, v);
            }
            if (proposed.expiresAt() == null || !proposed.expiresAt().isAfter(request.now())) {
                return PolicyDecision.deny(DecisionReason.DENY_EXPIRY_REQUIRED, permission, v);
            }
            if (proposed.expiresAt().isAfter(request.now().plus(policy.maxGrantDuration()))) {
                return PolicyDecision.deny(DecisionReason.DENY_EXPIRY_TOO_LONG, permission, v);
            }
            if (!grantor.authStrength().atLeast(policy.minAuthStrength())) {
                return PolicyDecision.deny(DecisionReason.DENY_AUTH_STRENGTH, permission, v);
            }
            if (policy.maxAuthAge() != null && (grantor.authenticatedAt() == null
                    || grantor.authenticatedAt().isAfter(request.now())
                    || grantor.authenticatedAt().plus(policy.maxAuthAge()).isBefore(request.now()))) {
                return PolicyDecision.deny(DecisionReason.DENY_STEP_UP_REQUIRED, permission, v);
            }
            return null;
        }

        if (grantor.tier() == SubjectTier.PRODUCT_ADMIN) {
            return null;
        }
        // Tenant admin from here on.
        if (policy.platformScoped() || !grantor.tenantId().equals(proposed.tenantId())) {
            return PolicyDecision.deny(DecisionReason.DENY_CROSS_TENANT, String.valueOf(proposed.tenantId()), v);
        }
        if (!holdsActive(request, delegationPermission, null)) {
            return PolicyDecision.deny(DecisionReason.DENY_EXCEEDS_GRANTOR, delegationPermission, v);
        }
        if (!holdsActive(request, permission, proposed)) {
            return PolicyDecision.deny(DecisionReason.DENY_EXCEEDS_GRANTOR, permission, v);
        }
        return null;
    }

    /**
     * Whether the grantor holds an active grant, in the proposed grant's tenant, for
     * {@code permission}; when {@code bounding} is given, that grant must also cover
     * the proposed devices, environments and lifetime.
     */
    private static boolean holdsActive(GrantRequest request, String permission, Grant bounding) {
        Grant proposed = request.proposed();
        for (Grant own : request.grantorGrants()) {
            if (!own.subjectId().equals(request.grantor().subjectId())
                    || !own.permissions().contains(permission)
                    || !Objects.equals(own.tenantId(), proposed.tenantId())
                    || !own.isActiveAt(request.now())) {
                continue;
            }
            if (bounding == null) {
                return true;
            }
            boolean devicesCovered = own.deviceIds() == null
                    || (bounding.deviceIds() != null && own.deviceIds().containsAll(bounding.deviceIds()));
            boolean environmentsCovered = own.environments() == null
                    || (bounding.environments() != null && own.environments().containsAll(bounding.environments()));
            boolean lifetimeCovered = own.expiresAt() == null
                    || (bounding.expiresAt() != null && !bounding.expiresAt().isAfter(own.expiresAt()));
            if (devicesCovered && environmentsCovered && lifetimeCovered) {
                return true;
            }
        }
        return false;
    }
}
