package com.iotee.platform.identity.rbac.policy;

import java.util.Objects;
import java.util.Optional;

/**
 * Rule-based authorization plus ABAC attributes. Deny by default; an
 * explicit deny wins over any grant and any tier.
 *
 * <p>Evaluation order (the first failing check decides, so the audit reason
 * is the most fundamental one):
 * <ol>
 *   <li>the permission has policy data in this {@link PolicySet} version;</li>
 *   <li>no explicit {@link DenyRule} matches;</li>
 *   <li>the subject's tier signs in through the client audience it presented,
 *       and that audience is allowed for the permission (admin and operator
 *       tokens are not interchangeable);</li>
 *   <li>the subject's tier may hold the permission at all (a grant never
 *       widens a tier, so an environment admin never acts as a system admin);</li>
 *   <li>the resource has the right shape (tenant vs platform) and a Tier 2
 *       subject acts only in its own tenant;</li>
 *   <li>device-scoped permissions name a device;</li>
 *   <li>the resource tenant holds the required entitlement;</li>
 *   <li>authentication strength (MFA) and freshness (step-up) suffice;</li>
 *   <li>an active, unexpired, unrevoked grant for this subject covers the
 *       permission, tenant (or platform), device and environment.</li>
 * </ol>
 *
 * <p>A Tier 1 subject has no implicit access to tenant resources: acting in
 * a tenant needs a grant naming that tenant, which is the explicit, audited
 * cross-tenant path ADR 0016 requires.
 */
public final class PolicyDecisionPoint {

    private final PolicySet policies;

    public PolicyDecisionPoint(PolicySet policies) {
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    public int policyVersion() {
        return policies.version();
    }

    public PolicyDecision decide(AccessRequest request) {
        Objects.requireNonNull(request, "request");
        int v = policies.version();
        Subject subject = request.subject();
        ResourceRef resource = request.resource();
        String permission = request.permission();

        Optional<PermissionPolicy> found = policies.policyFor(permission);
        if (found.isEmpty()) {
            return PolicyDecision.deny(DecisionReason.DENY_UNKNOWN_PERMISSION, permission, v);
        }
        PermissionPolicy policy = found.get();

        for (DenyRule rule : policies.denyRules()) {
            if (rule.matches(subject, permission, resource)) {
                return PolicyDecision.deny(DecisionReason.DENY_EXPLICIT_RULE, rule.ruleId(), v);
            }
        }
        if (subject.audience() != subject.tier().expectedAudience()) {
            return PolicyDecision.deny(DecisionReason.DENY_AUDIENCE_TIER_MISMATCH, subject.audience().name(), v);
        }
        if (!policy.audiences().contains(subject.audience())) {
            return PolicyDecision.deny(DecisionReason.DENY_AUDIENCE, subject.audience().name(), v);
        }
        if (!policy.tiers().contains(subject.tier())) {
            return PolicyDecision.deny(DecisionReason.DENY_TIER, subject.tier().name(), v);
        }
        if (policy.platformScoped()) {
            if (resource.tenantId() != null) {
                return PolicyDecision.deny(DecisionReason.DENY_SCOPE_MISMATCH, "platform permission on a tenant resource", v);
            }
        } else {
            if (resource.tenantId() == null) {
                return PolicyDecision.deny(DecisionReason.DENY_SCOPE_MISMATCH, "tenant permission without a tenant", v);
            }
            if (subject.tier().isTenantTier() && !resource.tenantId().equals(subject.tenantId())) {
                return PolicyDecision.deny(DecisionReason.DENY_CROSS_TENANT, resource.tenantId(), v);
            }
        }
        if (policy.deviceScoped() && resource.deviceId() == null) {
            return PolicyDecision.deny(DecisionReason.DENY_DEVICE_REQUIRED, permission, v);
        }
        if (policy.requiredEntitlement() != null
                && !request.tenantEntitlements().contains(policy.requiredEntitlement())) {
            return PolicyDecision.deny(DecisionReason.DENY_ENTITLEMENT, policy.requiredEntitlement(), v);
        }
        if (!subject.authStrength().atLeast(policy.minAuthStrength())) {
            return PolicyDecision.deny(DecisionReason.DENY_AUTH_STRENGTH, policy.minAuthStrength().name(), v);
        }
        if (policy.maxAuthAge() != null) {
            if (subject.authenticatedAt() == null || subject.authenticatedAt().isAfter(request.now())
                    || subject.authenticatedAt().plus(policy.maxAuthAge()).isBefore(request.now())) {
                return PolicyDecision.deny(DecisionReason.DENY_STEP_UP_REQUIRED, policy.maxAuthAge().toString(), v);
            }
        }
        return matchGrant(request, policy, v);
    }

    private static PolicyDecision matchGrant(AccessRequest request, PermissionPolicy policy, int v) {
        Subject subject = request.subject();
        ResourceRef resource = request.resource();
        DecisionReason bestDeny = DecisionReason.DENY_NO_ACTIVE_GRANT;
        String bestDetail = request.permission();
        for (Grant grant : request.subjectGrants()) {
            if (!grant.subjectId().equals(subject.subjectId()) || !grant.permissions().contains(request.permission())) {
                continue;
            }
            boolean scopeMatches = policy.platformScoped()
                    ? grant.tenantId() == null
                    : resource.tenantId().equals(grant.tenantId());
            if (!scopeMatches
                    || (policy.deviceScoped() && !grant.coversDevice(resource.deviceId()))
                    || (resource.environment() != null || grant.environments() != null)
                            && !grant.coversEnvironment(resource.environment())) {
                continue;
            }
            if (grant.isActiveAt(request.now())) {
                return new PolicyDecision(DecisionReason.PERMIT_ACTIVE_GRANT, grant.grantId(), v);
            }
            if (grant.isRevokedAt(request.now())) {
                bestDeny = DecisionReason.DENY_GRANT_REVOKED;
                bestDetail = grant.grantId();
            } else if (grant.isExpiredAt(request.now()) && bestDeny != DecisionReason.DENY_GRANT_REVOKED) {
                bestDeny = DecisionReason.DENY_GRANT_EXPIRED;
                bestDetail = grant.grantId();
            }
        }
        return PolicyDecision.deny(bestDeny, bestDetail, v);
    }
}
