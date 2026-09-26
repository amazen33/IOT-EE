package com.iotee.platform.identity.adapter.in.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.iotee.platform.contracts.identity.v1.GetTenantPermissionsRequest;
import com.iotee.platform.contracts.identity.v1.GetTenantPermissionsResponse;
import com.iotee.platform.contracts.identity.v1.TenantPermissionsServiceGrpc;
import com.iotee.platform.identity.adapter.out.persistence.InMemoryRoleAssignmentRepository;
import com.iotee.platform.identity.application.GetTenantPermissionsService;
import com.iotee.platform.identity.port.in.GetTenantPermissionsQuery;
import com.iotee.platform.identity.port.in.TenantPermissionsView;
import com.iotee.platform.identity.port.out.TokenSignatureVerifier;
import com.iotee.platform.identity.rbac.policy.AccessTokenClaims;
import com.iotee.platform.identity.rbac.policy.AccessTokenValidator;
import com.iotee.platform.identity.rbac.policy.ClientAudience;
import com.iotee.platform.identity.rbac.policy.IdentityPolicyV1;
import com.iotee.platform.identity.rbac.policy.PolicyDecisionPoint;
import com.iotee.platform.identity.rbac.policy.SubjectTier;
import com.iotee.platform.identity.rbac.policy.TokenRejectedException;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gRPC driving adapter over a real in-process gRPC transport (real
 * serialization and dispatch, no network port), backed by the real
 * application service, {@link BearerTokenServerInterceptor}, and a fake
 * {@code adapter.out.jwt.NimbusTokenSignatureVerifier} stand-in. Mirrors
 * {@code adapter.in.rest.TenantPermissionsControllerTest} case for case,
 * including the exhaustive authentication/authorization negatives that
 * live in {@code application.GetTenantPermissionsServiceTest} in full --
 * this class only proves the WIRE PATH (metadata in, status/description
 * out) matches that layer's decisions, plus the gateway-header case, which
 * only exists at this transport layer.
 */
class TenantPermissionsGrpcServiceTest {

    private static final String SYNTHETIC_TENANT = "synthetic-tenant-acme-001";
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final String ISSUER = "https://idp.synthetic.example/realms/iotee";
    private static final String ADMIN_AUDIENCE = "iotee-admin-console";
    private static final String ADMIN_CLIENT = "iotee-admin-console-client";
    private static final String VALID_ADMIN_TOKEN = "token-admin";

    private Server server;
    private ManagedChannel channel;
    private TenantPermissionsServiceGrpc.TenantPermissionsServiceBlockingStub stub;

    private static GetTenantPermissionsService realService() {
        AccessTokenClaims adminClaims = new AccessTokenClaims(ISSUER, Set.of(ADMIN_AUDIENCE), ADMIN_CLIENT,
                "synthetic-subject-admin", NOW.minusSeconds(60), null, NOW.plusSeconds(240), NOW.minusSeconds(60),
                Set.of("pwd"), SubjectTier.TENANT_ADMIN, SYNTHETIC_TENANT);
        TokenSignatureVerifier verifier = rawToken -> {
            if (VALID_ADMIN_TOKEN.equals(rawToken)) {
                return adminClaims;
            }
            throw new TokenRejectedException(TokenRejectedException.Reason.INVALID_SIGNATURE);
        };
        AccessTokenValidator adminValidator = new AccessTokenValidator(
                ISSUER, ADMIN_AUDIENCE, Set.of(), Set.of(ADMIN_CLIENT), ClientAudience.ADMIN_CONSOLE,
                EnumSet.of(SubjectTier.PRODUCT_ADMIN, SubjectTier.TENANT_ADMIN),
                Duration.ofMinutes(10), Duration.ofSeconds(30), Map.of());
        return new GetTenantPermissionsService(InMemoryRoleAssignmentRepository.withSyntheticSeed(), verifier,
                adminValidator, adminValidator, new PolicyDecisionPoint(IdentityPolicyV1.policySet()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void startInProcessServer() throws IOException {
        String name = InProcessServerBuilder.generateName();
        TenantPermissionsGrpcService adapter = new TenantPermissionsGrpcService(realService());
        server = InProcessServerBuilder.forName(name).directExecutor().addService(adapter)
                .intercept(new BearerTokenServerInterceptor()).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = TenantPermissionsServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopInProcessServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static GetTenantPermissionsRequest request(String tenantId, String subjectId) {
        return GetTenantPermissionsRequest.newBuilder().setTenantId(tenantId).setSubjectId(subjectId).build();
    }

    private TenantPermissionsServiceGrpc.TenantPermissionsServiceBlockingStub withHeaders(Metadata metadata) {
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static Metadata bearer(String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return metadata;
    }

    @Test
    void aValidTokenReturnsTheRequestedSubjectsPermissions() {
        GetTenantPermissionsResponse response = withHeaders(bearer(VALID_ADMIN_TOKEN))
                .getTenantPermissions(request(SYNTHETIC_TENANT, "synthetic-subject-viewer"));

        assertEquals(List.of("TENANT_READ"), response.getPermissionsList());
    }

    @Test
    void noAuthorizationMetadataIsUnauthenticated() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getTenantPermissions(request(SYNTHETIC_TENANT, "synthetic-subject-viewer")));

        assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
        assertEquals(TenantPermissionsGrpcService.AUTHENTICATION_CLIENT_SAFE_DETAIL, ex.getStatus().getDescription());
    }

    @Test
    void aForgedTokenIsUnauthenticated() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> withHeaders(bearer("not-a-real-token"))
                        .getTenantPermissions(request(SYNTHETIC_TENANT, "synthetic-subject-viewer")));

        assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
    }

    @Test
    void aSpoofedGatewayIdentityMetadataEntryAloneNeverGrantsAuthority() {
        // No authorization metadata at all -- only a plausible gateway-style identity claim, of
        // exactly the kind ADR 0016 Decision 4 says must stay advisory and this adapter never
        // reads. If it were consulted, this would succeed as synthetic-subject-admin; instead it
        // is rejected exactly like any other unauthenticated call.
        Metadata spoofed = new Metadata();
        spoofed.put(Metadata.Key.of("x-iotee-subject-id", Metadata.ASCII_STRING_MARSHALLER), "synthetic-subject-admin");
        spoofed.put(Metadata.Key.of("x-iotee-subject-tier", Metadata.ASCII_STRING_MARSHALLER), "PRODUCT_ADMIN");

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> withHeaders(spoofed).getTenantPermissions(request(SYNTHETIC_TENANT, "synthetic-subject-viewer")));

        assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
    }

    @Test
    void nonSyntheticTenantIdIsInvalidArgumentWithAFixedClientSafeDescription() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> withHeaders(bearer(VALID_ADMIN_TOKEN))
                        .getTenantPermissions(request("tenant-prod-4471", "synthetic-subject-admin")));

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
        assertEquals(TenantPermissionsGrpcService.CLIENT_SAFE_DETAIL, ex.getStatus().getDescription());
        assertFalse(String.valueOf(ex.getStatus().getDescription()).contains("synthetic-tenant-"),
                "the tenant-id validation pattern is internal detail and must not reach the client");
    }

    @Test
    void emptySubjectIdIsInvalidArgumentEvenWithNoToken() {
        // proto3: an unset subject_id arrives as "", which the application layer rejects before
        // any authentication is attempted -- so this needs no token at all.
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getTenantPermissions(GetTenantPermissionsRequest.newBuilder()
                        .setTenantId(SYNTHETIC_TENANT).build()));

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    }

    @Test
    void toResponsePreservesPermissionOrder() {
        GetTenantPermissionsResponse response = TenantPermissionsGrpcService.toResponse(
                new TenantPermissionsView("t", "s", List.of("A", "B", "C")));

        assertEquals(List.of("A", "B", "C"), response.getPermissionsList());
    }

    @Test
    void toQueryReadsTheBearerTokenFromContextNotFromTheRequest() {
        // Outside any interceptor-installed Context, BEARER_TOKEN_CONTEXT_KEY.get() is null; the
        // translation must default that to "", never null and never anything from the request
        // message itself (GetTenantPermissionsRequest carries no token field).
        assertEquals(new GetTenantPermissionsQuery("t", "s", ""), TenantPermissionsGrpcService.toQuery(request("t", "s")));
    }
}
