package com.iotee.platform.identity.port.out;

import com.iotee.platform.identity.rbac.policy.AccessTokenClaims;
import com.iotee.platform.identity.rbac.policy.TokenRejectedException;

/**
 * Outbound port: verifies an access token's signature and algorithm against
 * an issuer's published keys and returns its claims (ADR 0017 Decision 2,
 * {@code port.out}; the proposed identity ADR's Decision 1 and its rejected
 * alternative "hand-written JWT signature verification").
 *
 * <p>This is the ONLY seam through which a raw bearer token becomes trusted
 * {@link AccessTokenClaims}. Everything downstream of this port (
 * {@link com.iotee.platform.identity.rbac.policy.AccessTokenValidator},
 * {@link com.iotee.platform.identity.rbac.policy.PolicyDecisionPoint}) may
 * assume the signature and algorithm were already checked; neither of them
 * repeats that check.
 *
 * <p>Today's only implementation,
 * {@code adapter.out.jwt.NimbusTokenSignatureVerifier}, is built on a vetted
 * JOSE library (ADR 0016 Decision 5 / the identity ADR's rejected
 * alternative), confined to {@code adapter.out.jwt} by
 * {@code IdentityFrameworkFreedomArchitectureRulesTest}'s vendor-SDK
 * denylist. A future service or a future identity provider implements this
 * same interface in its own {@code adapter.out.jwt} package (ADR 0013:
 * copied, not shared) without changing any caller.
 */
public interface TokenSignatureVerifier {

    /**
     * Verifies {@code rawToken}'s signature and algorithm and returns its
     * claims, unvalidated beyond that (issuer, audience, expiry, tier and
     * tenant consistency are {@link com.iotee.platform.identity.rbac.policy.AccessTokenValidator}'s
     * job, not this port's).
     *
     * @throws TokenRejectedException with
     *     {@link TokenRejectedException.Reason#INVALID_SIGNATURE} if the
     *     token cannot be parsed as a JWT, its signature does not verify
     *     against the issuer's published keys, its algorithm is not one
     *     this verifier was configured to accept, or a required claim
     *     needed just to build {@link AccessTokenClaims} (for example
     *     {@code sub} or {@code exp}) is absent. The message never
     *     contains the token.
     */
    AccessTokenClaims verify(String rawToken);
}
