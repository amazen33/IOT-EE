package com.iotee.platform.identity.port.in;

/**
 * Thrown by an inbound port when the caller's bearer token is absent or was
 * rejected: no signature (missing token), a forged or algorithm-substituted
 * signature, an expired, not-yet-valid, wrong-issuer, wrong-audience,
 * forbidden-audience, unknown-client, too-long-lived, or wrong-tier token
 * (ADR 0017 Decision 2; the proposed identity ADR's Decision 1).
 *
 * <p>Lives in {@code port.in}, not {@code rbac.policy}, for the same reason
 * {@link InvalidQueryException} does: driving adapters may depend on
 * {@code port.in} only, so the port -- not {@code rbac.policy} -- has to
 * name the failure they translate into a transport status. The application
 * layer catches {@code rbac.policy.TokenRejectedException} (from the token
 * verifier) and {@code rbac.policy.AccessTokenValidator}'s own claims
 * rejection and rethrows both as this one, keeping the original as the
 * {@linkplain #getCause() cause} for operator logging -- never for the
 * client (see {@link #getMessage()}).
 *
 * <p>Every driving adapter maps this to its transport's "not authenticated"
 * status: HTTP 401 (REST), {@code UNAUTHENTICATED} (gRPC). Distinct from
 * {@link AuthorizationDeniedException} (HTTP 403 / {@code PERMISSION_DENIED}):
 * this is "who are you, really" failing; that is "you are who you say, but
 * you may not do this."
 */
public final class AuthenticationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException(String clientSafeMessage, Throwable cause) {
        super(clientSafeMessage, cause);
    }
}
