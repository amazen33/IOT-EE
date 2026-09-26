package com.iotee.platform.identity.rbac.policy;

/**
 * The OIDC client / token audience a subject authenticated through.
 * Admin and operator consoles use separate clients, audiences, sessions and
 * policies (requirements addendum, "Consoles and identity"); a token for one
 * is never accepted by the other.
 */
public enum ClientAudience {
    ADMIN_CONSOLE,
    OPERATOR_CONSOLE
}
