package com.iotee.platform.identity.rbac.policy;

/** Why a decision came out as it did; recorded in the audit trail with the policy version. */
public enum DecisionReason {
    PERMIT_ACTIVE_GRANT,
    PERMIT_GRANT_VALID,

    DENY_UNKNOWN_PERMISSION,
    DENY_EXPLICIT_RULE,
    DENY_AUDIENCE_TIER_MISMATCH,
    DENY_AUDIENCE,
    DENY_TIER,
    DENY_SCOPE_MISMATCH,
    DENY_CROSS_TENANT,
    DENY_DEVICE_REQUIRED,
    DENY_ENTITLEMENT,
    DENY_AUTH_STRENGTH,
    DENY_STEP_UP_REQUIRED,
    DENY_GRANT_REVOKED,
    DENY_GRANT_EXPIRED,
    DENY_NO_ACTIVE_GRANT,

    DENY_GRANTOR_MISMATCH,
    DENY_SELF_GRANT,
    DENY_GRANTOR_TIER,
    DENY_PRIVILEGED_GRANTOR,
    DENY_APPROVAL_REQUIRED,
    DENY_EXPIRY_REQUIRED,
    DENY_EXPIRY_TOO_LONG,
    DENY_EXCEEDS_GRANTOR;

    public boolean isPermit() {
        return name().startsWith("PERMIT_");
    }
}
