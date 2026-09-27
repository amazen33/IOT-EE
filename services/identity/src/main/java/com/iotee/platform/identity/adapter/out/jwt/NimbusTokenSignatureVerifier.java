package com.iotee.platform.identity.adapter.out.jwt;

import com.iotee.platform.identity.port.out.TokenSignatureVerifier;
import com.iotee.platform.identity.rbac.policy.AccessTokenClaims;
import com.iotee.platform.identity.rbac.policy.SubjectTier;
import com.iotee.platform.identity.rbac.policy.TokenRejectedException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link TokenSignatureVerifier} built on Nimbus JOSE + JWT, a vetted JOSE
 * library (ADR 0016 Decision 5; the proposed identity ADR's rejected
 * alternative "hand-written JWT signature verification"). The ONLY class in
 * {@code services/identity} that imports {@code com.nimbusds..}; confined
 * here by {@code IdentityFrameworkFreedomArchitectureRulesTest}'s
 * vendor-SDK denylist, the same way {@code org.keycloak} and
 * {@code com.stripe} are confined to their own adapters.
 *
 * <p><b>Algorithm pinning, not negotiation.</b> {@link JWSVerificationKeySelector}
 * is constructed with exactly one {@link JWSAlgorithm} and the issuer's
 * {@link JWKSource}; it accepts a signature only if it verifies under that
 * one algorithm against a key from that source. This is what rejects
 * {@code alg=none} (there is no "none" key selector to satisfy) and
 * algorithm substitution (an attacker-chosen {@code alg} that happens to
 * verify against unrelated key material, e.g. presenting an RSA public key
 * as an HMAC secret): neither can produce a match against the one pinned
 * algorithm and the one real {@link JWKSource}.
 *
 * <p><b>Division of labor with {@code rbac.policy}:</b> this class verifies
 * signature and algorithm and maps claims; it does not re-implement any of
 * {@link com.iotee.platform.identity.rbac.policy.AccessTokenValidator}'s
 * business rules (issuer, audience, expiry, client, tier, tenant
 * consistency) and must not -- those stay in one place, framework-free, and
 * unit-tested there.
 *
 * <p><b>Verification status:</b> this class compiles against Nimbus 9.37.4
 * in the local Maven gate. An in-process JWKS test exercises signed
 * acceptance, unsigned/substituted/forged rejection and new-key-id
 * rotation. A real identity provider and signed-token REST/gRPC round trip
 * remain unverified.
 */
public final class NimbusTokenSignatureVerifier implements TokenSignatureVerifier {

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final String authorizedPartyClaimName;
    private final String authTimeClaimName;
    private final String amrClaimName;
    private final String tierClaimName;
    private final Map<String, SubjectTier> tierClaimValues;
    private final String tenantClaimName;

    /**
     * @param jwksUri              the issuer's published JWKS endpoint (configurable per ADR 0016
     *                             Decision 5 / the identity ADR's Decision 1: "configurable
     *                             issuer/JWKS", never hardcoded)
     * @param expectedAlgorithm    the ONE JWS algorithm this verifier accepts (for example
     *                             {@code RS256}); never {@code none}, and never inferred from the
     *                             token itself
     * @param httpConnectTimeout   JWKS-fetch connect timeout
     * @param httpReadTimeout      JWKS-fetch read timeout
     * @param authorizedPartyClaimName the claim holding {@code azp} (usually {@code "azp"})
     * @param authTimeClaimName    the claim holding {@code auth_time} as a NumericDate (usually
     *                             {@code "auth_time"}); its absence is not an error here, only in
     *                             {@code AccessTokenValidator} if a policy needs step-up freshness
     * @param amrClaimName         the claim holding the authentication-methods array (usually
     *                             {@code "amr"})
     * @param tierClaimName        the provider claim that carries this platform's tier (ADR 0016
     *                             Decision 3: which claim holds tier/tenant is provider
     *                             configuration)
     * @param tierClaimValues      maps that claim's raw string values to {@link SubjectTier}; an
     *                             unmapped value maps to no tier (the token is then rejected by
     *                             whichever {@code AccessTokenValidator} sees it, as a missing
     *                             claim)
     * @param tenantClaimName      the provider claim that carries the tenant id; absent for Tier 1
     */
    public NimbusTokenSignatureVerifier(
            URI jwksUri,
            String expectedAlgorithm,
            Duration httpConnectTimeout,
            Duration httpReadTimeout,
            String authorizedPartyClaimName,
            String authTimeClaimName,
            String amrClaimName,
            String tierClaimName,
            Map<String, SubjectTier> tierClaimValues,
            String tenantClaimName) {
        Objects.requireNonNull(jwksUri, "jwksUri");
        Objects.requireNonNull(expectedAlgorithm, "expectedAlgorithm");
        Objects.requireNonNull(httpConnectTimeout, "httpConnectTimeout");
        Objects.requireNonNull(httpReadTimeout, "httpReadTimeout");
        this.authorizedPartyClaimName = Objects.requireNonNull(authorizedPartyClaimName, "authorizedPartyClaimName");
        this.authTimeClaimName = Objects.requireNonNull(authTimeClaimName, "authTimeClaimName");
        this.amrClaimName = Objects.requireNonNull(amrClaimName, "amrClaimName");
        this.tierClaimName = Objects.requireNonNull(tierClaimName, "tierClaimName");
        this.tierClaimValues = Map.copyOf(Objects.requireNonNull(tierClaimValues, "tierClaimValues"));
        this.tenantClaimName = Objects.requireNonNull(tenantClaimName, "tenantClaimName");

        DefaultResourceRetriever retriever =
                new DefaultResourceRetriever((int) httpConnectTimeout.toMillis(), (int) httpReadTimeout.toMillis());
        JWKSource<SecurityContext> jwkSource;
        try {
            jwkSource = new RemoteJWKSet<>(jwksUri.toURL(), retriever);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("jwksUri is not a valid URL: " + jwksUri, e);
        }

        DefaultJWTProcessor<SecurityContext> defaultProcessor = new DefaultJWTProcessor<>();
        // The one and only accepted (algorithm, key source) pair -- see this class's Javadoc on
        // algorithm pinning. Override Nimbus's default claims verifier: issuer, audience,
        // expiry and time policy belong to AccessTokenValidator, after signature verification.
        defaultProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.parse(expectedAlgorithm), jwkSource));
        defaultProcessor.setJWTClaimsSetVerifier((claimsSet, context) -> { });
        this.processor = defaultProcessor;
    }

    @Override
    public AccessTokenClaims verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new TokenRejectedException(TokenRejectedException.Reason.MISSING_CLAIM);
        }

        JWTClaimsSet claims;
        try {
            claims = processor.process(rawToken, null);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            // Every failure this call can raise -- unparseable JWT, signature that does not
            // verify, an algorithm other than the one pinned above, an unreachable or empty
            // JWKS -- is reported identically: INVALID_SIGNATURE, with no detail that could help
            // an attacker distinguish "wrong key" from "wrong algorithm" from "malformed token".
            throw new TokenRejectedException(TokenRejectedException.Reason.INVALID_SIGNATURE);
        }

        try {
            return mapClaims(claims);
        } catch (ParseException e) {
            // A claim needed just to construct AccessTokenClaims (e.g. a malformed amr array)
            // could not be read; treated as a missing claim, same as if it were absent.
            throw new TokenRejectedException(TokenRejectedException.Reason.MISSING_CLAIM);
        }
    }

    private AccessTokenClaims mapClaims(JWTClaimsSet claims) throws ParseException {
        String issuer = claims.getIssuer();
        List<String> audienceList = claims.getAudience();
        Set<String> audiences = audienceList == null ? Set.of() : new HashSet<>(audienceList);
        String authorizedParty = claims.getStringClaim(authorizedPartyClaimName);
        String subject = claims.getSubject();
        Instant issuedAt = claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant();
        Instant notBefore = claims.getNotBeforeTime() == null ? null : claims.getNotBeforeTime().toInstant();
        Instant expiresAt = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();

        Long authTimeSeconds = claims.getLongClaim(authTimeClaimName);
        Instant authTime = authTimeSeconds == null ? null : Instant.ofEpochSecond(authTimeSeconds);

        List<String> amrList = claims.getStringListClaim(amrClaimName);
        Set<String> amr = amrList == null ? Set.of() : new HashSet<>(amrList);

        String rawTier = claims.getStringClaim(tierClaimName);
        SubjectTier tier = rawTier == null ? null : tierClaimValues.get(rawTier);

        String tenantId = claims.getStringClaim(tenantClaimName);

        return new AccessTokenClaims(
                issuer, audiences, authorizedParty, subject, issuedAt, notBefore, expiresAt, authTime, amr, tier, tenantId);
    }
}
