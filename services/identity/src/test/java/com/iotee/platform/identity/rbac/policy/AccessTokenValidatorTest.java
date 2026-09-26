package com.iotee.platform.identity.rbac.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Claim validation for synthetic, already-signature-verified tokens. Covers
 * admin/operator token interchange, issuer, client, lifetime and tier/tenant
 * consistency. Signature and algorithm verification are not exercised here:
 * no verifier adapter exists yet.
 */
class AccessTokenValidatorTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final String ISSUER = "https://idp.synthetic.example/realms/iotee";
    private static final String ADMIN_API = "iotee-admin-api";
    private static final String OPERATOR_API = "iotee-operator-api";
    private static final Map<String, AuthStrength> AMR = Map.of(
            "pwd", AuthStrength.SINGLE_FACTOR, "otp", AuthStrength.OTP, "hwk", AuthStrength.PHISHING_RESISTANT);

    private final AccessTokenValidator adminApi = new AccessTokenValidator(ISSUER, ADMIN_API, Set.of(OPERATOR_API),
            Set.of("iotee-admin-console"), ClientAudience.ADMIN_CONSOLE,
            Set.of(SubjectTier.PRODUCT_ADMIN, SubjectTier.TENANT_ADMIN),
            Duration.ofMinutes(10), Duration.ofSeconds(30), AMR);

    private final AccessTokenValidator operatorApi = new AccessTokenValidator(ISSUER, OPERATOR_API, Set.of(ADMIN_API),
            Set.of("iotee-operator-console"), ClientAudience.OPERATOR_CONSOLE,
            Set.of(SubjectTier.TENANT_OPERATOR, SubjectTier.PRODUCT_OPERATOR),
            Duration.ofMinutes(10), Duration.ofSeconds(30), AMR);

    private static AccessTokenClaims adminToken() {
        return new AccessTokenClaims(ISSUER, Set.of(ADMIN_API), "iotee-admin-console", "synthetic-ta-1",
                NOW.minusSeconds(60), null, NOW.plusSeconds(240), NOW.minusSeconds(120),
                Set.of("pwd", "otp"), SubjectTier.TENANT_ADMIN, "synthetic-tenant-a");
    }

    private static AccessTokenClaims operatorToken() {
        return new AccessTokenClaims(ISSUER, Set.of(OPERATOR_API), "iotee-operator-console", "synthetic-op-1",
                NOW.minusSeconds(60), null, NOW.plusSeconds(240), NOW.minusSeconds(120),
                Set.of("pwd"), SubjectTier.TENANT_OPERATOR, "synthetic-tenant-a");
    }

    private static AccessTokenClaims with(AccessTokenClaims c, String issuer, Set<String> aud, String azp,
            Instant iat, Instant nbf, Instant exp, SubjectTier tier, String tenant) {
        return new AccessTokenClaims(issuer, aud, azp, c.subject(), iat, nbf, exp, c.authTime(), c.amr(), tier, tenant);
    }

    private static void assertRejected(TokenRejectedException.Reason reason, Runnable call) {
        TokenRejectedException ex = assertThrows(TokenRejectedException.class, call::run);
        assertEquals(reason, ex.reason());
    }

    @Test
    void aValidAdminTokenMapsToAnAdminConsoleSubjectWithItsMfaStrength() {
        Subject subject = adminApi.validate(adminToken(), NOW);

        assertEquals("synthetic-ta-1", subject.subjectId());
        assertEquals(SubjectTier.TENANT_ADMIN, subject.tier());
        assertEquals("synthetic-tenant-a", subject.tenantId());
        assertEquals(ClientAudience.ADMIN_CONSOLE, subject.audience());
        assertEquals(AuthStrength.OTP, subject.authStrength());
        assertEquals(NOW.minusSeconds(120), subject.authenticatedAt());
    }

    @Test
    void anOperatorTokenIsRejectedByTheAdminApi() {
        assertRejected(TokenRejectedException.Reason.FORBIDDEN_AUDIENCE, () -> adminApi.validate(operatorToken(), NOW));
    }

    @Test
    void anAdminTokenIsRejectedByTheOperatorApi() {
        assertRejected(TokenRejectedException.Reason.FORBIDDEN_AUDIENCE, () -> operatorApi.validate(adminToken(), NOW));
    }

    @Test
    void aTokenCarryingBothAudiencesIsRejectedByBoth() {
        AccessTokenClaims both = with(adminToken(), ISSUER, Set.of(ADMIN_API, OPERATOR_API), "iotee-admin-console",
                NOW.minusSeconds(60), null, NOW.plusSeconds(240), SubjectTier.TENANT_ADMIN, "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.FORBIDDEN_AUDIENCE, () -> adminApi.validate(both, NOW));
        assertRejected(TokenRejectedException.Reason.FORBIDDEN_AUDIENCE, () -> operatorApi.validate(both, NOW));
    }

    @Test
    void aTokenForSomeOtherApiIsRejected() {
        AccessTokenClaims other = with(adminToken(), ISSUER, Set.of("some-other-api"), "iotee-admin-console",
                NOW.minusSeconds(60), null, NOW.plusSeconds(240), SubjectTier.TENANT_ADMIN, "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.WRONG_AUDIENCE, () -> adminApi.validate(other, NOW));
    }

    @Test
    void anOperatorTierTokenIssuedToTheAdminAudienceIsStillRejected() {
        AccessTokenClaims mislabelled = with(operatorToken(), ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                NOW.minusSeconds(60), null, NOW.plusSeconds(240), SubjectTier.TENANT_OPERATOR, "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.TIER_NOT_ACCEPTED, () -> adminApi.validate(mislabelled, NOW));
    }

    @Test
    void aTokenFromTheOperatorClientIsRejectedByTheAdminApiEvenWithTheAdminAudience() {
        AccessTokenClaims wrongClient = with(adminToken(), ISSUER, Set.of(ADMIN_API), "iotee-operator-console",
                NOW.minusSeconds(60), null, NOW.plusSeconds(240), SubjectTier.TENANT_ADMIN, "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.UNKNOWN_CLIENT, () -> adminApi.validate(wrongClient, NOW));
    }

    @Test
    void theWrongIssuerIsRejected() {
        AccessTokenClaims foreign = with(adminToken(), "https://idp.synthetic.example/realms/other", Set.of(ADMIN_API),
                "iotee-admin-console", NOW.minusSeconds(60), null, NOW.plusSeconds(240), SubjectTier.TENANT_ADMIN,
                "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.WRONG_ISSUER, () -> adminApi.validate(foreign, NOW));
    }

    @Test
    void expiryNotBeforeAndIssuedAtAreEnforcedWithClockSkew() {
        AccessTokenClaims expired = with(adminToken(), ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                NOW.minusSeconds(60), null, NOW.minusSeconds(31), SubjectTier.TENANT_ADMIN, "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.EXPIRED, () -> adminApi.validate(expired, NOW));

        AccessTokenClaims withinSkew = with(adminToken(), ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                NOW.minusSeconds(60), null, NOW.minusSeconds(29), SubjectTier.TENANT_ADMIN, "synthetic-tenant-a");
        assertEquals("synthetic-ta-1", adminApi.validate(withinSkew, NOW).subjectId());

        AccessTokenClaims notYet = with(adminToken(), ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                NOW.minusSeconds(60), NOW.plusSeconds(120), NOW.plusSeconds(240), SubjectTier.TENANT_ADMIN,
                "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.NOT_YET_VALID, () -> adminApi.validate(notYet, NOW));

        AccessTokenClaims future = with(adminToken(), ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                NOW.plusSeconds(120), null, NOW.plusSeconds(300), SubjectTier.TENANT_ADMIN, "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.ISSUED_IN_FUTURE, () -> adminApi.validate(future, NOW));
    }

    @Test
    void longLivedAccessTokensAreRejected() {
        AccessTokenClaims longLived = with(adminToken(), ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                NOW.minusSeconds(60), null, NOW.minusSeconds(60).plus(Duration.ofHours(1)), SubjectTier.TENANT_ADMIN,
                "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.LIFETIME_TOO_LONG, () -> adminApi.validate(longLived, NOW));
    }

    @Test
    void tierAndTenantClaimsMustAgree() {
        AccessTokenClaims tenantAdminWithoutTenant = with(adminToken(), ISSUER, Set.of(ADMIN_API),
                "iotee-admin-console", NOW.minusSeconds(60), null, NOW.plusSeconds(240), SubjectTier.TENANT_ADMIN, null);
        assertRejected(TokenRejectedException.Reason.TENANT_CLAIM_INVALID,
                () -> adminApi.validate(tenantAdminWithoutTenant, NOW));

        AccessTokenClaims systemAdminWithTenant = with(adminToken(), ISSUER, Set.of(ADMIN_API),
                "iotee-admin-console", NOW.minusSeconds(60), null, NOW.plusSeconds(240), SubjectTier.PRODUCT_ADMIN,
                "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.TENANT_CLAIM_INVALID,
                () -> adminApi.validate(systemAdminWithTenant, NOW));

        AccessTokenClaims systemAdmin = with(adminToken(), ISSUER, Set.of(ADMIN_API),
                "iotee-admin-console", NOW.minusSeconds(60), null, NOW.plusSeconds(240), SubjectTier.PRODUCT_ADMIN, null);
        assertNull(adminApi.validate(systemAdmin, NOW).tenantId());
    }

    @Test
    void missingRequiredClaimsAreRejected() {
        AccessTokenClaims noSubject = new AccessTokenClaims(ISSUER, Set.of(ADMIN_API), "iotee-admin-console", " ",
                NOW.minusSeconds(60), null, NOW.plusSeconds(240), null, Set.of(), SubjectTier.TENANT_ADMIN,
                "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.MISSING_CLAIM, () -> adminApi.validate(noSubject, NOW));

        AccessTokenClaims noExpiry = new AccessTokenClaims(ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                "synthetic-ta-1", NOW.minusSeconds(60), null, null, null, Set.of(), SubjectTier.TENANT_ADMIN,
                "synthetic-tenant-a");
        assertRejected(TokenRejectedException.Reason.MISSING_CLAIM, () -> adminApi.validate(noExpiry, NOW));
    }

    @Test
    void thePhishingResistantFactorIsRecognisedButMissingAuthTimeRemainsUnknown() {
        AccessTokenClaims passkey = new AccessTokenClaims(ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                "synthetic-sysadmin-1", NOW.minusSeconds(60), null, NOW.plusSeconds(240), null,
                Set.of("hwk", "pwd"), SubjectTier.PRODUCT_ADMIN, null);
        Subject subject = adminApi.validate(passkey, NOW);
        assertEquals(AuthStrength.PHISHING_RESISTANT, subject.authStrength());
        assertNull(subject.authenticatedAt());

        AccessTokenClaims unknownMethod = new AccessTokenClaims(ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                "synthetic-sysadmin-1", NOW.minusSeconds(60), null, NOW.plusSeconds(240), null,
                Set.of("sms"), SubjectTier.PRODUCT_ADMIN, null);
        assertEquals(AuthStrength.SINGLE_FACTOR, adminApi.validate(unknownMethod, NOW).authStrength());
    }

    @Test
    void futureAuthTimeAndInconsistentClaimTimesAreRejected() {
        AccessTokenClaims futureAuthTime = new AccessTokenClaims(ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                "synthetic-sysadmin-1", NOW.minusSeconds(60), null, NOW.plusSeconds(240), NOW.plusSeconds(60),
                Set.of("pwd", "otp"), SubjectTier.PRODUCT_ADMIN, null);
        assertRejected(TokenRejectedException.Reason.INVALID_CLAIM_TIME,
                () -> adminApi.validate(futureAuthTime, NOW));

        AccessTokenClaims expiresBeforeIssue = new AccessTokenClaims(ISSUER, Set.of(ADMIN_API), "iotee-admin-console",
                "synthetic-sysadmin-1", NOW, null, NOW.minusSeconds(1), NOW.minusSeconds(2), Set.of("pwd", "otp"),
                SubjectTier.PRODUCT_ADMIN, null);
        assertRejected(TokenRejectedException.Reason.INVALID_CLAIM_TIME,
                () -> adminApi.validate(expiresBeforeIssue, NOW));
    }

    @Test
    void misconfigurationIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new AccessTokenValidator(ISSUER, ADMIN_API,
                Set.of(ADMIN_API), Set.of("c"), ClientAudience.ADMIN_CONSOLE, Set.of(SubjectTier.TENANT_ADMIN),
                Duration.ofMinutes(5), Duration.ZERO, AMR));
        assertThrows(IllegalArgumentException.class, () -> new AccessTokenValidator(ISSUER, ADMIN_API,
                Set.of(), Set.of("c"), ClientAudience.ADMIN_CONSOLE, Set.of(SubjectTier.TENANT_OPERATOR),
                Duration.ofMinutes(5), Duration.ZERO, AMR));
    }
}
