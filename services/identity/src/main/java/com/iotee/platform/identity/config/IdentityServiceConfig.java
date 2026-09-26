package com.iotee.platform.identity.config;

import com.iotee.platform.identity.adapter.in.grpc.GrpcServerRunner;
import com.iotee.platform.identity.adapter.in.grpc.TenantPermissionsGrpcService;
import com.iotee.platform.identity.adapter.out.jwt.NimbusTokenSignatureVerifier;
import com.iotee.platform.identity.adapter.out.persistence.InMemoryRoleAssignmentRepository;
import com.iotee.platform.identity.application.GetTenantPermissionsService;
import com.iotee.platform.identity.port.in.GetTenantPermissionsUseCase;
import com.iotee.platform.identity.port.out.RoleAssignmentRepository;
import com.iotee.platform.identity.port.out.TokenSignatureVerifier;
import com.iotee.platform.identity.rbac.policy.AccessTokenValidator;
import com.iotee.platform.identity.rbac.policy.AuthStrength;
import com.iotee.platform.identity.rbac.policy.ClientAudience;
import com.iotee.platform.identity.rbac.policy.IdentityPolicyV1;
import com.iotee.platform.identity.rbac.policy.PolicyDecisionPoint;
import com.iotee.platform.identity.rbac.policy.SubjectTier;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Composition root (ADR 0017 Decision 2, {@code config}): the only place
 * that knows which adapter implements which port. Everything inside the
 * hexagon ({@code domain}, {@code rbac}, {@code port.*},
 * {@code application}) stays free of Spring annotations; this class
 * wires it with plain constructor calls.
 *
 * <p>The REST controller and its exception advice are picked up by
 * component scanning (they are Spring MVC types by nature); the gRPC
 * adapter is a plain grpc-java class, so it is constructed here and
 * served by the adapter's own {@code GrpcServerRunner}, started and
 * stopped with the Spring context by {@link GrpcServerLifecycle}. This
 * class imports no {@code io.grpc} or {@code org.springframework.web}
 * type (ADR 0017 Decision 4); it DOES import {@code com.nimbusds.jose.JWSAlgorithm}
 * (a plain enum-like value type used only to name the accepted algorithm),
 * which is consistent with {@code IdentityFrameworkFreedomArchitectureRulesTest}'s
 * vendor-SDK denylist confining {@code com.nimbusds} to
 * {@code adapter.out.jwt} for BUSINESS LOGIC -- this class is the
 * composition root, not business logic, and never calls a Nimbus API
 * beyond selecting this one value; {@code IdentityHexagonalArchitectureRulesTest}
 * places no restriction on {@code config}'s own dependencies (only
 * adapters, ports and the application layer are restricted).
 *
 * <p><b>Authentication and authorization wiring (PF-C C3):</b> the
 * permit-all {@code rbac.AbacContext} stub the proposed identity ADR
 * forbids on any tenant-facing endpoint is no longer wired here -- see
 * this class's git history for the bean it replaced. In its place:
 * {@link #tokenSignatureVerifier} (a vetted JOSE library, confined to its
 * own adapter), two {@link AccessTokenValidator}s -- one per console
 * audience, per the proposed identity ADR's Decision 1 ("separate admin
 * and operator OIDC clients, audiences, sessions and policies") -- and one
 * {@link PolicyDecisionPoint} over {@link IdentityPolicyV1}'s versioned,
 * deny-by-default policy data (Decision 4).
 *
 * <p>Every value below is externally configurable via
 * {@code application.yml} / environment (never hardcoded), per the
 * identity ADR's Decision 1 ("configurable issuer/JWKS") and its rejected
 * alternative "hand-written JWT signature verification" -- but the
 * defaults are placeholders (there is no real identity provider deployed
 * yet; ADR 0016 names Keycloak as the first candidate, still an open
 * question). Do not treat the default {@code https://issuer.invalid/...}
 * values as anything other than "must be overridden before this service
 * is deployed anywhere real" -- {@code IdentityServiceConfig} cannot know
 * that on its own, which is exactly why this is configuration and not a
 * constant.
 */
@Configuration
public class IdentityServiceConfig {

    /**
     * Authentication-methods-reference to authentication-strength mapping (proposed identity ADR
     * Decision 3: WebAuthn/security-key preferred, TOTP the floor, SMS/email OTP never the
     * privileged default -- so neither appears here). Fixed, not externally configurable: unlike
     * issuer/JWKS/audience/client (deployment facts), this is a security decision this service
     * owns. A raw {@code amr} value this map does not recognize is simply absent from the
     * strongest-match scan in {@code AccessTokenClaims.strength}, so it can never inflate strength.
     */
    private static final Map<String, AuthStrength> AMR_MAPPING = Map.of(
            "pwd", AuthStrength.SINGLE_FACTOR,
            "otp", AuthStrength.OTP,
            "mfa", AuthStrength.OTP,
            "hwk", AuthStrength.PHISHING_RESISTANT,
            "webauthn", AuthStrength.PHISHING_RESISTANT);

    @Bean
    public RoleAssignmentRepository roleAssignmentRepository() {
        return InMemoryRoleAssignmentRepository.withSyntheticSeed();
    }

    /**
     * The one seam through which a raw bearer token becomes trusted claims (ADR 0016 Decision 5).
     * Both console validators share it: one identity provider/issuer is assumed to serve both the
     * admin and operator OIDC clients (a single JWKS endpoint covers both clients' tokens, since a
     * JWKS is keyed by issuer, not by client or audience); a deployment with genuinely separate
     * issuers per console needs a second verifier bean and a second wiring here, not a code change
     * to {@link NimbusTokenSignatureVerifier} or {@link com.iotee.platform.identity.application.GetTenantPermissionsService}.
     */
    @Bean
    public TokenSignatureVerifier tokenSignatureVerifier(
            @Value("${iotee.identity.auth.jwks-uri:https://issuer.invalid/.well-known/jwks.json}") URI jwksUri,
            @Value("${iotee.identity.auth.algorithm:RS256}") String algorithm,
            @Value("${iotee.identity.auth.jwks-connect-timeout:PT5S}") Duration jwksConnectTimeout,
            @Value("${iotee.identity.auth.jwks-read-timeout:PT5S}") Duration jwksReadTimeout,
            @Value("${iotee.identity.auth.authorized-party-claim:azp}") String authorizedPartyClaim,
            @Value("${iotee.identity.auth.auth-time-claim:auth_time}") String authTimeClaim,
            @Value("${iotee.identity.auth.amr-claim:amr}") String amrClaim,
            @Value("${iotee.identity.auth.tier-claim:https://iotee.io/tier}") String tierClaim,
            @Value("${iotee.identity.auth.tenant-claim:https://iotee.io/tenant_id}") String tenantClaim) {
        return new NimbusTokenSignatureVerifier(
                jwksUri, algorithm, jwksConnectTimeout, jwksReadTimeout,
                authorizedPartyClaim, authTimeClaim, amrClaim, tierClaim, tierClaimValues(), tenantClaim);
    }

    /**
     * The provider's raw {@code tier} claim string values, mapped to this platform's
     * {@link SubjectTier}. Fixed for the same reason {@link #AMR_MAPPING} is: this is this
     * service's own vocabulary, not a deployment fact. An identity provider whose tier claim uses
     * different literal values needs a claim TRANSFORMATION at the provider (a protocol mapper),
     * not a change here.
     */
    private static Map<String, SubjectTier> tierClaimValues() {
        return Map.of(
                "product_admin", SubjectTier.PRODUCT_ADMIN,
                "product_operator", SubjectTier.PRODUCT_OPERATOR,
                "tenant_admin", SubjectTier.TENANT_ADMIN,
                "tenant_operator", SubjectTier.TENANT_OPERATOR);
    }

    /** Validates tokens presented to the admin console API: {@code PRODUCT_ADMIN}/{@code TENANT_ADMIN} only. */
    @Bean
    public AccessTokenValidator adminConsoleAccessTokenValidator(
            @Value("${iotee.identity.auth.admin.issuer:https://issuer.invalid/}") String issuer,
            @Value("${iotee.identity.auth.admin.audience:iotee-admin-console}") String adminAudience,
            @Value("${iotee.identity.auth.operator.audience:iotee-operator-console}") String operatorAudience,
            @Value("${iotee.identity.auth.admin.allowed-clients:iotee-admin-console-client}") Set<String> allowedClients,
            @Value("${iotee.identity.auth.max-token-lifetime:PT10M}") Duration maxLifetime,
            @Value("${iotee.identity.auth.clock-skew:PT30S}") Duration clockSkew) {
        return new AccessTokenValidator(
                issuer, adminAudience, Set.of(operatorAudience), allowedClients, ClientAudience.ADMIN_CONSOLE,
                EnumSet.of(SubjectTier.PRODUCT_ADMIN, SubjectTier.TENANT_ADMIN), maxLifetime, clockSkew, AMR_MAPPING);
    }

    /** Validates tokens presented to the operator console API: {@code PRODUCT_OPERATOR}/{@code TENANT_OPERATOR} only. */
    @Bean
    public AccessTokenValidator operatorConsoleAccessTokenValidator(
            @Value("${iotee.identity.auth.operator.issuer:https://issuer.invalid/}") String issuer,
            @Value("${iotee.identity.auth.operator.audience:iotee-operator-console}") String operatorAudience,
            @Value("${iotee.identity.auth.admin.audience:iotee-admin-console}") String adminAudience,
            @Value("${iotee.identity.auth.operator.allowed-clients:iotee-operator-console-client}") Set<String> allowedClients,
            @Value("${iotee.identity.auth.max-token-lifetime:PT10M}") Duration maxLifetime,
            @Value("${iotee.identity.auth.clock-skew:PT30S}") Duration clockSkew) {
        return new AccessTokenValidator(
                issuer, operatorAudience, Set.of(adminAudience), allowedClients, ClientAudience.OPERATOR_CONSOLE,
                EnumSet.of(SubjectTier.PRODUCT_OPERATOR, SubjectTier.TENANT_OPERATOR), maxLifetime, clockSkew, AMR_MAPPING);
    }

    /** This service's own versioned policy data (ADR 0017 Decision 2), deny-by-default. */
    @Bean
    public PolicyDecisionPoint policyDecisionPoint() {
        return new PolicyDecisionPoint(IdentityPolicyV1.policySet());
    }

    @Bean
    public GetTenantPermissionsUseCase getTenantPermissionsUseCase(
            RoleAssignmentRepository roleAssignmentRepository,
            TokenSignatureVerifier tokenSignatureVerifier,
            @Qualifier("adminConsoleAccessTokenValidator") AccessTokenValidator adminConsoleAccessTokenValidator,
            @Qualifier("operatorConsoleAccessTokenValidator") AccessTokenValidator operatorConsoleAccessTokenValidator,
            PolicyDecisionPoint policyDecisionPoint) {
        return new GetTenantPermissionsService(
                roleAssignmentRepository, tokenSignatureVerifier, adminConsoleAccessTokenValidator,
                operatorConsoleAccessTokenValidator, policyDecisionPoint, Clock.systemUTC());
    }

    @Bean
    public TenantPermissionsGrpcService tenantPermissionsGrpcService(GetTenantPermissionsUseCase useCase) {
        return new TenantPermissionsGrpcService(useCase);
    }

    @Bean
    public GrpcServerLifecycle grpcServerLifecycle(
            TenantPermissionsGrpcService tenantPermissionsGrpcService,
            @Value("${iotee.identity.grpc.enabled:true}") boolean enabled,
            @Value("${iotee.identity.grpc.port:9091}") int port) {
        return new GrpcServerLifecycle(enabled, new GrpcServerRunner(port, tenantPermissionsGrpcService));
    }
}
