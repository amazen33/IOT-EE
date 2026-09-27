package com.iotee.platform.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iotee.platform.identity.adapter.out.persistence.InMemoryRoleAssignmentRepository;
import com.iotee.platform.identity.domain.TenantId;
import com.iotee.platform.identity.domain.TenantIdValidationException;
import com.iotee.platform.identity.port.in.AuthenticationFailedException;
import com.iotee.platform.identity.port.in.AuthorizationDeniedException;
import com.iotee.platform.identity.port.in.GetTenantPermissionsQuery;
import com.iotee.platform.identity.port.in.InvalidQueryException;
import com.iotee.platform.identity.port.in.TenantPermissionsView;
import com.iotee.platform.identity.port.out.RoleAssignmentRepository;
import com.iotee.platform.identity.port.out.TokenSignatureVerifier;
import com.iotee.platform.identity.rbac.Permission;
import com.iotee.platform.identity.rbac.policy.AccessTokenClaims;
import com.iotee.platform.identity.rbac.policy.AccessTokenValidator;
import com.iotee.platform.identity.rbac.policy.AuthStrength;
import com.iotee.platform.identity.rbac.policy.ClientAudience;
import com.iotee.platform.identity.rbac.policy.IdentityPolicyV1;
import com.iotee.platform.identity.rbac.policy.PolicyDecisionPoint;
import com.iotee.platform.identity.rbac.policy.SubjectTier;
import com.iotee.platform.identity.rbac.policy.TokenRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link GetTenantPermissionsService} with a bare JUnit test --
 * no Spring context, no real JOSE library (a {@link FakeTokenSignatureVerifier}
 * test double stands in for {@code adapter.out.jwt.NimbusTokenSignatureVerifier};
 * signature/algorithm verification itself is out of scope here and belongs
 * to that adapter). Everything downstream of the token -- claims
 * validation via the real {@link AccessTokenValidator}, authorization via
 * the real {@link PolicyDecisionPoint} over {@link IdentityPolicyV1} -- is
 * the genuine article, unit-tested end to end through this one service.
 *
 * <p>PF-C C3: the permit-all {@code rbac.AbacContext} stub this service
 * used to be built with is gone; every test below authenticates and
 * authorizes for real. The required negative cases (absent, forged,
 * expired, wrong-issuer, wrong-audience, cross-tenant token; revoked
 * grant; authority never coming from the requested subject id) are named
 * explicitly so a reviewer can find each one without reading the whole
 * class.
 */
class GetTenantPermissionsServiceTest {

    private static final String SYNTHETIC_TENANT = "synthetic-tenant-acme-001";
    private static final String OTHER_TENANT = "synthetic-tenant-other-002";

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String ISSUER = "https://idp.synthetic.example/realms/iotee";
    private static final String WRONG_ISSUER = "https://not-the-real-idp.synthetic.example/realms/iotee";
    private static final String ADMIN_AUDIENCE = "iotee-admin-console";
    private static final String OPERATOR_AUDIENCE = "iotee-operator-console";
    private static final String UNRELATED_AUDIENCE = "some-other-service";
    private static final String ADMIN_CLIENT = "iotee-admin-console-client";
    private static final String OPERATOR_CLIENT = "iotee-operator-console-client";
    private static final Map<String, AuthStrength> AMR = Map.of("pwd", AuthStrength.SINGLE_FACTOR, "otp", AuthStrength.OTP);

    private static final AccessTokenValidator ADMIN_VALIDATOR = new AccessTokenValidator(
            ISSUER, ADMIN_AUDIENCE, Set.of(OPERATOR_AUDIENCE), Set.of(ADMIN_CLIENT), ClientAudience.ADMIN_CONSOLE,
            EnumSet.of(SubjectTier.PRODUCT_ADMIN, SubjectTier.TENANT_ADMIN),
            Duration.ofMinutes(10), Duration.ofSeconds(30), AMR);
    private static final AccessTokenValidator OPERATOR_VALIDATOR = new AccessTokenValidator(
            ISSUER, OPERATOR_AUDIENCE, Set.of(ADMIN_AUDIENCE), Set.of(OPERATOR_CLIENT), ClientAudience.OPERATOR_CONSOLE,
            EnumSet.of(SubjectTier.PRODUCT_OPERATOR, SubjectTier.TENANT_OPERATOR),
            Duration.ofMinutes(10), Duration.ofSeconds(30), AMR);

    /** Stands in for {@code adapter.out.jwt.NimbusTokenSignatureVerifier}: no signing, no network. */
    private static final class FakeTokenSignatureVerifier implements TokenSignatureVerifier {
        private final Map<String, AccessTokenClaims> byToken;

        FakeTokenSignatureVerifier(Map<String, AccessTokenClaims> byToken) {
            this.byToken = byToken;
        }

        @Override
        public AccessTokenClaims verify(String rawToken) {
            AccessTokenClaims claims = byToken.get(rawToken);
            if (claims == null) {
                // Any token this fake was not explicitly told about is exactly what a real
                // verifier would call an unrecognized/forged signature.
                throw new TokenRejectedException(TokenRejectedException.Reason.INVALID_SIGNATURE);
            }
            return claims;
        }
    }

    private static AccessTokenClaims claims(SubjectTier tier, String subjectId, String tenantId,
            Set<String> audiences, String issuer, Instant expiresAt) {
        String azp = tier.expectedAudience() == ClientAudience.ADMIN_CONSOLE ? ADMIN_CLIENT : OPERATOR_CLIENT;
        Instant issuedAt = expiresAt.isBefore(NOW) ? expiresAt.minusSeconds(60) : NOW.minusSeconds(60);
        return new AccessTokenClaims(issuer, audiences, azp, subjectId, issuedAt, null, expiresAt,
                issuedAt, Set.of("pwd"), tier, tier.isTenantTier() ? tenantId : null);
    }

    private static GetTenantPermissionsService service(
            RoleAssignmentRepository roleAssignments, Map<String, AccessTokenClaims> tokens) {
        return new GetTenantPermissionsService(roleAssignments, new FakeTokenSignatureVerifier(tokens),
                ADMIN_VALIDATOR, OPERATOR_VALIDATOR, new PolicyDecisionPoint(IdentityPolicyV1.policySet()), CLOCK);
    }

    private static GetTenantPermissionsService serviceWithSyntheticSeed(Map<String, AccessTokenClaims> tokens) {
        return service(InMemoryRoleAssignmentRepository.withSyntheticSeed(), tokens);
    }

    /** A valid admin-console token for {@code synthetic-subject-admin}, TENANT_ADMIN in {@link #SYNTHETIC_TENANT}. */
    private static final String ADMIN_TOKEN = "token-admin";
    /** A valid operator-console token for {@code synthetic-subject-viewer}, TENANT_VIEWER in {@link #SYNTHETIC_TENANT}. */
    private static final String VIEWER_OPERATOR_TOKEN = "token-viewer-operator";

    private static Map<String, AccessTokenClaims> defaultTokens() {
        Map<String, AccessTokenClaims> tokens = new HashMap<>();
        tokens.put(ADMIN_TOKEN, claims(SubjectTier.TENANT_ADMIN, "synthetic-subject-admin", SYNTHETIC_TENANT,
                Set.of(ADMIN_AUDIENCE), ISSUER, NOW.plusSeconds(240)));
        tokens.put(VIEWER_OPERATOR_TOKEN, claims(SubjectTier.TENANT_OPERATOR, "synthetic-subject-viewer", SYNTHETIC_TENANT,
                Set.of(OPERATOR_AUDIENCE), ISSUER, NOW.plusSeconds(240)));
        return tokens;
    }

    // ------------------------------------------------------------- happy path

    @Test
    void authenticatedAdminCallerReadsAnotherSubjectsPermissionsInTheSharedTenant() {
        GetTenantPermissionsService service = serviceWithSyntheticSeed(defaultTokens());

        TenantPermissionsView view = service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-viewer", ADMIN_TOKEN));

        assertEquals(SYNTHETIC_TENANT, view.tenantId());
        assertEquals("synthetic-subject-viewer", view.subjectId());
        assertEquals(List.of("TENANT_READ"), view.permissions());
    }

    @Test
    void tenantReadAcceptsAllFourTiersSoAnOperatorTokenAlsoWorks() {
        GetTenantPermissionsService service = serviceWithSyntheticSeed(defaultTokens());

        TenantPermissionsView view = service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-admin", VIEWER_OPERATOR_TOKEN));

        assertEquals(List.of("TENANT_MANAGE", "TENANT_READ"), view.permissions());
    }

    @Test
    void unassignedTargetSubjectReceivesNoPermissions() {
        GetTenantPermissionsService service = serviceWithSyntheticSeed(defaultTokens());

        TenantPermissionsView view = service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-unassigned", ADMIN_TOKEN));

        assertTrue(view.permissions().isEmpty());
    }

    // ---------------------------------------------------- query validation (before auth)

    @Test
    void nonSyntheticTenantIdIsRejectedAsAnInvalidQueryWrappingTheDomainFailure() {
        GetTenantPermissionsService service = serviceWithSyntheticSeed(defaultTokens());

        InvalidQueryException ex = assertThrows(InvalidQueryException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery("tenant-prod-4471", "synthetic-subject-admin", ADMIN_TOKEN)));

        assertInstanceOf(TenantIdValidationException.class, ex.getCause());
    }

    @Test
    void invalidTenantIdIsRejectedBeforeAnyTokenVerificationOrRbacLookup() {
        List<String> verifierCalls = new ArrayList<>();
        TokenSignatureVerifier recordingVerifier = rawToken -> {
            verifierCalls.add(rawToken);
            throw new TokenRejectedException(TokenRejectedException.Reason.INVALID_SIGNATURE);
        };
        List<String> lookups = new ArrayList<>();
        RoleAssignmentRepository recordingRepository = (tenantId, subjectId) -> {
            lookups.add(subjectId);
            return Set.of();
        };
        GetTenantPermissionsService service = new GetTenantPermissionsService(recordingRepository, recordingVerifier,
                ADMIN_VALIDATOR, OPERATOR_VALIDATOR, new PolicyDecisionPoint(IdentityPolicyV1.policySet()), CLOCK);

        assertThrows(InvalidQueryException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery("not-synthetic-at-all", "synthetic-subject-admin", ADMIN_TOKEN)));

        assertTrue(verifierCalls.isEmpty(), "tenant/subject validation must run before any token verification");
        assertTrue(lookups.isEmpty(), "tenant/subject validation must run before any RBAC lookup");
    }

    @Test
    void blankSubjectIdIsRejectedBeforeAnyTokenVerification() {
        GetTenantPermissionsService service = serviceWithSyntheticSeed(defaultTokens());

        assertThrows(InvalidQueryException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "", ADMIN_TOKEN)));
        assertThrows(InvalidQueryException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "   ", ADMIN_TOKEN)));
    }

    // ---------------------------------------------------------- authentication negatives

    @Test
    void absentTokenIsRejected() {
        GetTenantPermissionsService service = serviceWithSyntheticSeed(defaultTokens());

        AuthenticationFailedException ex = assertThrows(AuthenticationFailedException.class,
                () -> service.getTenantPermissions(new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-viewer", "")));

        assertInstanceOf(TokenRejectedException.class, ex.getCause());
        assertEquals(TokenRejectedException.Reason.MISSING_CLAIM, ((TokenRejectedException) ex.getCause()).reason());
    }

    @Test
    void forgedTokenIsRejected() {
        GetTenantPermissionsService service = serviceWithSyntheticSeed(defaultTokens());

        AuthenticationFailedException ex = assertThrows(AuthenticationFailedException.class,
                () -> service.getTenantPermissions(new GetTenantPermissionsQuery(
                        SYNTHETIC_TENANT, "synthetic-subject-viewer", "this-signature-was-never-issued-by-the-idp")));

        assertInstanceOf(TokenRejectedException.class, ex.getCause());
        assertEquals(TokenRejectedException.Reason.INVALID_SIGNATURE, ((TokenRejectedException) ex.getCause()).reason());
    }

    @Test
    void expiredTokenIsRejected() {
        Map<String, AccessTokenClaims> tokens = defaultTokens();
        tokens.put("token-expired", claims(SubjectTier.TENANT_ADMIN, "synthetic-subject-admin", SYNTHETIC_TENANT,
                Set.of(ADMIN_AUDIENCE), ISSUER, NOW.minusSeconds(3600)));
        GetTenantPermissionsService service = serviceWithSyntheticSeed(tokens);

        AuthenticationFailedException ex = assertThrows(AuthenticationFailedException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-viewer", "token-expired")));

        assertEquals(TokenRejectedException.Reason.EXPIRED, ((TokenRejectedException) ex.getCause()).reason());
    }

    @Test
    void wrongIssuerTokenIsRejected() {
        Map<String, AccessTokenClaims> tokens = defaultTokens();
        tokens.put("token-wrong-issuer", claims(SubjectTier.TENANT_ADMIN, "synthetic-subject-admin", SYNTHETIC_TENANT,
                Set.of(ADMIN_AUDIENCE), WRONG_ISSUER, NOW.plusSeconds(240)));
        GetTenantPermissionsService service = serviceWithSyntheticSeed(tokens);

        AuthenticationFailedException ex = assertThrows(AuthenticationFailedException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-viewer", "token-wrong-issuer")));

        assertEquals(TokenRejectedException.Reason.WRONG_ISSUER, ((TokenRejectedException) ex.getCause()).reason());
    }

    @Test
    void wrongAudienceTokenIsRejected() {
        // Claims a tier that would route to the admin validator, but its real aud is
        // neither console's -- WRONG_AUDIENCE, not FORBIDDEN_AUDIENCE (which is reserved for the
        // OTHER console's own audience -- see aForbiddenOperatorAudienceOnTheAdminValidatorIsRejected).
        Map<String, AccessTokenClaims> tokens = defaultTokens();
        tokens.put("token-wrong-audience", claims(SubjectTier.TENANT_ADMIN, "synthetic-subject-admin", SYNTHETIC_TENANT,
                Set.of(UNRELATED_AUDIENCE), ISSUER, NOW.plusSeconds(240)));
        GetTenantPermissionsService service = serviceWithSyntheticSeed(tokens);

        AuthenticationFailedException ex = assertThrows(AuthenticationFailedException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-viewer", "token-wrong-audience")));

        assertEquals(TokenRejectedException.Reason.WRONG_AUDIENCE, ((TokenRejectedException) ex.getCause()).reason());
    }

    @Test
    void anOperatorAudienceTokenPresentedAsAdminTierIsRejectedAsForbiddenNotJustWrong() {
        // An operator-console token whose tier claim was tampered with to claim TENANT_ADMIN:
        // routed to the admin validator by the claimed tier, but its real aud is the operator
        // console's -- which the admin validator carries as an explicitly FORBIDDEN audience.
        Map<String, AccessTokenClaims> tokens = defaultTokens();
        tokens.put("token-forbidden-audience", claims(SubjectTier.TENANT_ADMIN, "synthetic-subject-admin", SYNTHETIC_TENANT,
                Set.of(OPERATOR_AUDIENCE), ISSUER, NOW.plusSeconds(240)));
        GetTenantPermissionsService service = serviceWithSyntheticSeed(tokens);

        AuthenticationFailedException ex = assertThrows(AuthenticationFailedException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-viewer", "token-forbidden-audience")));

        assertEquals(TokenRejectedException.Reason.FORBIDDEN_AUDIENCE, ((TokenRejectedException) ex.getCause()).reason());
    }

    // ----------------------------------------------------------- authorization negatives

    @Test
    void crossTenantTokenIsDenied() {
        // A real, validly-signed, correctly-issued TENANT_ADMIN token for a DIFFERENT tenant
        // than the one being queried. ADR 0016 Decision 6 / the identity ADR's Decision 4: a
        // Tier 2 subject acts only in its own tenant, even to read permissions.
        Map<String, AccessTokenClaims> tokens = defaultTokens();
        tokens.put("token-other-tenant", claims(SubjectTier.TENANT_ADMIN, "synthetic-subject-other-tenant-admin",
                OTHER_TENANT, Set.of(ADMIN_AUDIENCE), ISSUER, NOW.plusSeconds(240)));
        GetTenantPermissionsService service = serviceWithSyntheticSeed(tokens);

        AuthorizationDeniedException ex = assertThrows(AuthorizationDeniedException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-viewer", "token-other-tenant")));

        assertNull(ex.getCause(), "the policy decision's detail must never be exposed as the client-visible cause");
        assertFalse(ex.getMessage().toLowerCase().contains("tenant"),
                "the client-safe message must not leak which check failed");
    }

    @Test
    void revokedRoleAssignmentDeniesAccessOnTheNextRequest() {
        // Simulates revocation the way this endpoint actually observes it: the caller's RBAC role
        // assignment (this endpoint's only grant source -- see GetTenantPermissionsService's
        // Javadoc) is removed between two calls. PolicyDecisionPointTest.aRevokedGrantDeniesFromTheMomentOfRevocation
        // separately proves the underlying explicit-Grant-revocation semantics this call relies on
        // once a persisted Grant store exists; this test proves revocation already reaches this
        // endpoint end-to-end today, through the bridge.
        InMemoryRoleAssignmentRepository repository = InMemoryRoleAssignmentRepository.withSyntheticSeed();
        GetTenantPermissionsService service = service(repository, defaultTokens());
        GetTenantPermissionsQuery query =
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-viewer", ADMIN_TOKEN);

        // Before revocation: the caller (synthetic-subject-admin) holds TENANT_READ in this
        // tenant via its RBAC role, so the call succeeds.
        TenantPermissionsView beforeRevocation = service.getTenantPermissions(query);
        assertEquals(List.of("TENANT_READ"), beforeRevocation.permissions());

        repository.revoke(SYNTHETIC_TENANT, "synthetic-subject-admin");

        assertThrows(AuthorizationDeniedException.class, () -> service.getTenantPermissions(query),
                "the very next request after revocation must be denied -- no caching of the earlier grant");
    }

    @Test
    void callerAuthorityComesFromTheTokenNeverFromTheRequestedSubjectId() {
        // The caller authenticates as an unassigned subject with no role in this tenant; the
        // REQUESTED subjectId names a fully-privileged subject. If the requested subjectId could
        // grant authority, this would succeed and return synthetic-subject-admin's permissions --
        // exactly the gateway-header-style attack this proves is impossible: no field of the
        // request other than the verified token can authenticate or authorize the caller.
        Map<String, AccessTokenClaims> tokens = defaultTokens();
        tokens.put("token-unassigned", claims(SubjectTier.TENANT_OPERATOR, "synthetic-subject-unassigned",
                SYNTHETIC_TENANT, Set.of(OPERATOR_AUDIENCE), ISSUER, NOW.plusSeconds(240)));
        GetTenantPermissionsService service = serviceWithSyntheticSeed(tokens);

        assertThrows(AuthorizationDeniedException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery(SYNTHETIC_TENANT, "synthetic-subject-admin", "token-unassigned")));
    }

    @Test
    void theCallersOwnGrantIsBridgedFromItsOwnTenantOnly() {
        // synthetic-subject-admin holds TENANT_ADMIN in SYNTHETIC_TENANT but nothing in
        // OTHER_TENANT (RbacRegistry's cross-tenant-key fix). A token correctly issued for
        // OTHER_TENANT (internally consistent, real signature, real issuer) still cannot read
        // OTHER_TENANT's permissions, because the bridge finds no role assignment there.
        Map<String, AccessTokenClaims> tokens = defaultTokens();
        tokens.put("token-admin-other-tenant", claims(SubjectTier.TENANT_ADMIN, "synthetic-subject-admin",
                OTHER_TENANT, Set.of(ADMIN_AUDIENCE), ISSUER, NOW.plusSeconds(240)));
        GetTenantPermissionsService service = serviceWithSyntheticSeed(tokens);

        assertThrows(AuthorizationDeniedException.class, () -> service.getTenantPermissions(
                new GetTenantPermissionsQuery(OTHER_TENANT, "synthetic-subject-viewer", "token-admin-other-tenant")));
    }
}
