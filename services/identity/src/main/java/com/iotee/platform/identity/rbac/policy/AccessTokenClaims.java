package com.iotee.platform.identity.rbac.policy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Claims of an access token whose signature and algorithm have already been
 * verified against the issuer's keys by a verifier adapter. Which provider
 * claim holds tenant and tier is provider configuration (ADR 0016 Decision
 * 3); the adapter maps them into these fields.
 *
 * @param issuer          {@code iss}
 * @param audiences       {@code aud}
 * @param authorizedParty {@code azp}: the OIDC client the token was issued to
 * @param subject         {@code sub}
 * @param issuedAt        {@code iat}
 * @param notBefore       {@code nbf}; may be null
 * @param expiresAt       {@code exp}
 * @param authTime        {@code auth_time}: when the user last authenticated
 * @param amr             {@code amr}: authentication methods used
 * @param tier            mapped tier claim
 * @param tenantId        mapped tenant claim; null for Tier 1
 */
public record AccessTokenClaims(
        String issuer,
        Set<String> audiences,
        String authorizedParty,
        String subject,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt,
        Instant authTime,
        Set<String> amr,
        SubjectTier tier,
        String tenantId) {

    public AccessTokenClaims {
        audiences = audiences == null ? Set.of() : Set.copyOf(audiences);
        amr = amr == null ? Set.of() : Set.copyOf(amr);
    }

    /** Strongest authentication strength the provider-configured {@code amr} mapping yields. */
    AuthStrength strength(Map<String, AuthStrength> amrMapping) {
        AuthStrength best = AuthStrength.SINGLE_FACTOR;
        for (String method : amr) {
            AuthStrength mapped = amrMapping.get(method);
            if (mapped != null && mapped.atLeast(best)) {
                best = mapped;
            }
        }
        return best;
    }
}
