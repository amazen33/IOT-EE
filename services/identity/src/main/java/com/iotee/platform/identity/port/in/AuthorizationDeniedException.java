package com.iotee.platform.identity.port.in;

/**
 * Thrown by an inbound port when an authenticated caller's
 * {@code rbac.policy.PolicyDecisionPoint} decision is a deny: unknown
 * permission, an explicit deny rule, an audience/tier mismatch, wrong tier,
 * cross-tenant access, a missing entitlement, insufficient authentication
 * strength or step-up freshness, or no active grant covering the permission
 * (ADR 0017 Decision 2; the proposed identity ADR's Decision 4).
 *
 * <p>Lives in {@code port.in}, not {@code rbac.policy}, for the same reason
 * {@link InvalidQueryException} does. The application layer never returns
 * {@code rbac.policy.PolicyDecision}'s deny reason or the grant/rule id to
 * the client (both may be operationally sensitive); it logs them and
 * rethrows this exception with a fixed, client-safe message.
 *
 * <p>Every driving adapter maps this to its transport's "not authorized"
 * status: HTTP 403 (REST), {@code PERMISSION_DENIED} (gRPC). Distinct from
 * {@link AuthenticationFailedException} (HTTP 401 /
 * {@code UNAUTHENTICATED}): that is "who are you, really" failing; this is
 * "you are who you say, but you may not do this."
 */
public final class AuthorizationDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthorizationDeniedException(String clientSafeMessage, Throwable cause) {
        super(clientSafeMessage, cause);
    }
}
