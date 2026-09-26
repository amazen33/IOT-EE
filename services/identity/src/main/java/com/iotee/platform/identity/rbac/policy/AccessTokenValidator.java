package com.iotee.platform.identity.rbac.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates verified access-token claims for one API (one audience) and maps
 * them to a {@link Subject}. Each service configures its own instance and
 * validates tokens itself (ADR 0016 Decision 4: the service is the trust
 * boundary; gateway headers are advisory).
 *
 * <p>Rejects: wrong issuer; an audience other than this API's, or any
 * audience configured as forbidden for this API (an operator-console token
 * presented to an admin API, or the reverse, including multi-audience
 * tokens); a client ({@code azp}) not configured for this API; expired,
 * not-yet-valid or future-issued tokens beyond the clock skew; tokens whose
 * lifetime exceeds the configured maximum (short-lived access tokens);
 * tiers this API does not serve; and tenant claims inconsistent with the
 * tier.
 *
 * <p>Precondition, not implemented here: the signature and algorithm were
 * verified against the issuer's published keys, with {@code alg=none} and
 * algorithm substitution rejected. That is the job of a verifier adapter
 * built on a vetted JOSE library; it does not exist yet.
 */
public final class AccessTokenValidator {

    private final String expectedIssuer;
    private final String expectedAudience;
    private final Set<String> forbiddenAudiences;
    private final Set<String> allowedClients;
    private final Set<SubjectTier> acceptedTiers;
    private final ClientAudience clientAudience;
    private final Duration maxLifetime;
    private final Duration clockSkew;
    private final Map<String, AuthStrength> amrMapping;

    public AccessTokenValidator(
            String expectedIssuer,
            String expectedAudience,
            Set<String> forbiddenAudiences,
            Set<String> allowedClients,
            ClientAudience clientAudience,
            Set<SubjectTier> acceptedTiers,
            Duration maxLifetime,
            Duration clockSkew,
            Map<String, AuthStrength> amrMapping) {
        this.expectedIssuer = Objects.requireNonNull(expectedIssuer, "expectedIssuer");
        this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience");
        this.forbiddenAudiences = Set.copyOf(forbiddenAudiences);
        this.allowedClients = Set.copyOf(allowedClients);
        this.clientAudience = Objects.requireNonNull(clientAudience, "clientAudience");
        this.acceptedTiers = Set.copyOf(acceptedTiers);
        this.maxLifetime = Objects.requireNonNull(maxLifetime, "maxLifetime");
        this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
        this.amrMapping = Map.copyOf(amrMapping);
        if (this.forbiddenAudiences.contains(expectedAudience)) {
            throw new IllegalArgumentException("the expected audience cannot also be forbidden");
        }
        for (SubjectTier tier : this.acceptedTiers) {
            if (tier.expectedAudience() != clientAudience) {
                throw new IllegalArgumentException(tier + " does not sign in through " + clientAudience);
            }
        }
        if (maxLifetime.isNegative() || maxLifetime.isZero() || clockSkew.isNegative()) {
            throw new IllegalArgumentException("maxLifetime must be positive and clockSkew non-negative");
        }
    }

    /** Validates {@code claims} at {@code now}; returns the mapped subject or throws {@link TokenRejectedException}. */
    public Subject validate(AccessTokenClaims claims, Instant now) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(now, "now");
        if (claims.issuer() == null || claims.subject() == null || claims.subject().isBlank()
                || claims.issuedAt() == null || claims.expiresAt() == null || claims.tier() == null
                || claims.authorizedParty() == null || claims.audiences().isEmpty()) {
            throw new TokenRejectedException(TokenRejectedException.Reason.MISSING_CLAIM);
        }
        if (!expectedIssuer.equals(claims.issuer())) {
            throw new TokenRejectedException(TokenRejectedException.Reason.WRONG_ISSUER);
        }
        for (String audience : claims.audiences()) {
            if (forbiddenAudiences.contains(audience)) {
                throw new TokenRejectedException(TokenRejectedException.Reason.FORBIDDEN_AUDIENCE);
            }
        }
        if (!claims.audiences().contains(expectedAudience)) {
            throw new TokenRejectedException(TokenRejectedException.Reason.WRONG_AUDIENCE);
        }
        if (!allowedClients.contains(claims.authorizedParty())) {
            throw new TokenRejectedException(TokenRejectedException.Reason.UNKNOWN_CLIENT);
        }
        if (claims.expiresAt().isBefore(claims.issuedAt())
                || (claims.notBefore() != null && claims.notBefore().isAfter(claims.expiresAt()))
                || (claims.authTime() != null && (claims.authTime().isAfter(now.plus(clockSkew))
                        || claims.authTime().isAfter(claims.issuedAt().plus(clockSkew))))) {
            throw new TokenRejectedException(TokenRejectedException.Reason.INVALID_CLAIM_TIME);
        }
        if (!now.isBefore(claims.expiresAt().plus(clockSkew))) {
            throw new TokenRejectedException(TokenRejectedException.Reason.EXPIRED);
        }
        if (claims.notBefore() != null && now.plus(clockSkew).isBefore(claims.notBefore())) {
            throw new TokenRejectedException(TokenRejectedException.Reason.NOT_YET_VALID);
        }
        if (now.plus(clockSkew).isBefore(claims.issuedAt())) {
            throw new TokenRejectedException(TokenRejectedException.Reason.ISSUED_IN_FUTURE);
        }
        if (Duration.between(claims.issuedAt(), claims.expiresAt()).compareTo(maxLifetime) > 0) {
            throw new TokenRejectedException(TokenRejectedException.Reason.LIFETIME_TOO_LONG);
        }
        if (!acceptedTiers.contains(claims.tier())) {
            throw new TokenRejectedException(TokenRejectedException.Reason.TIER_NOT_ACCEPTED);
        }
        boolean hasTenant = claims.tenantId() != null && !claims.tenantId().isBlank();
        if (claims.tier().isTenantTier() != hasTenant) {
            throw new TokenRejectedException(TokenRejectedException.Reason.TENANT_CLAIM_INVALID);
        }
        return new Subject(claims.subject(), claims.tier(), hasTenant ? claims.tenantId() : null,
                clientAudience, claims.strength(amrMapping), claims.authTime());
    }
}
