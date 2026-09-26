package com.iotee.platform.identity.rbac.policy;

/**
 * The four roles of ADR 0016 Decision 6 and the proposed roles ADR, kept
 * distinct. A tier is a property of the authenticated subject, never
 * something a grant confers: holding an environment-admin channel grant
 * does not make anyone a {@link #PRODUCT_ADMIN}.
 */
public enum SubjectTier {
    /** System admin (Tier 1). */
    PRODUCT_ADMIN,
    /** System operator (Tier 1). */
    PRODUCT_OPERATOR,
    /** Tenant admin (Tier 2). */
    TENANT_ADMIN,
    /** Tenant operator (Tier 2). */
    TENANT_OPERATOR;

    /** Tier 2 subjects are bound to exactly one tenant. */
    public boolean isTenantTier() {
        return this == TENANT_ADMIN || this == TENANT_OPERATOR;
    }

    /** The client audience this tier signs in through (separate admin and operator clients). */
    public ClientAudience expectedAudience() {
        return (this == PRODUCT_ADMIN || this == TENANT_ADMIN)
                ? ClientAudience.ADMIN_CONSOLE
                : ClientAudience.OPERATOR_CONSOLE;
    }
}
