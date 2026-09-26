package com.iotee.platform.identity.rbac.policy;

/** An access token's claims failed validation. The message never contains the token. */
public final class TokenRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the token was rejected; safe to log and to count. */
    public enum Reason {
        /**
         * The verifier adapter rejected the token before any claim was even
         * read: a signature that does not verify against the issuer's
         * published keys, an algorithm other than the one this API accepts
         * ({@code alg=none} and algorithm substitution included), or the
         * token could not be parsed as a JWT at all. Raised only by a
         * {@code port.out.TokenSignatureVerifier} implementation, never by
         * {@link AccessTokenValidator} (which only ever sees claims whose
         * signature has already verified).
         */
        INVALID_SIGNATURE,
        MISSING_CLAIM,
        WRONG_ISSUER,
        WRONG_AUDIENCE,
        FORBIDDEN_AUDIENCE,
        UNKNOWN_CLIENT,
        INVALID_CLAIM_TIME,
        EXPIRED,
        NOT_YET_VALID,
        ISSUED_IN_FUTURE,
        LIFETIME_TOO_LONG,
        TIER_NOT_ACCEPTED,
        TENANT_CLAIM_INVALID
    }

    private final Reason reason;

    public TokenRejectedException(Reason reason) {
        super("access token rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
