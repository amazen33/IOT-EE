package com.iotee.platform.identity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iotee.platform.identity.adapter.in.grpc.GrpcServerRunner;
import com.iotee.platform.identity.adapter.in.grpc.TenantPermissionsGrpcService;
import com.iotee.platform.identity.adapter.out.persistence.InMemoryRoleAssignmentRepository;
import com.iotee.platform.identity.application.GetTenantPermissionsService;
import com.iotee.platform.identity.port.out.TokenSignatureVerifier;
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
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle edge cases for the gRPC server, without a Spring context.
 *
 * <p>Not about authentication/authorization (see
 * {@code application.GetTenantPermissionsServiceTest} for the exhaustive
 * negative cases); the fake JOSE verifier here exists only so a
 * {@link GetTenantPermissionsService} can be constructed at all now that
 * the permit-all {@code rbac.AbacContext} stub is gone.
 */
class GrpcServerLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final String ISSUER = "https://idp.synthetic.example/realms/iotee";
    private static final String ADMIN_AUDIENCE = "iotee-admin-console";

    private static GetTenantPermissionsService noopService() {
        TokenSignatureVerifier alwaysRejects = rawToken -> {
            throw new TokenRejectedException(TokenRejectedException.Reason.MISSING_CLAIM);
        };
        AccessTokenValidator adminValidator = new AccessTokenValidator(
                ISSUER, ADMIN_AUDIENCE, Set.of(), Set.of("iotee-admin-console-client"), ClientAudience.ADMIN_CONSOLE,
                EnumSet.of(SubjectTier.PRODUCT_ADMIN, SubjectTier.TENANT_ADMIN),
                Duration.ofMinutes(10), Duration.ofSeconds(30), Map.of());
        return new GetTenantPermissionsService(InMemoryRoleAssignmentRepository.withSyntheticSeed(), alwaysRejects,
                adminValidator, adminValidator, new PolicyDecisionPoint(IdentityPolicyV1.policySet()),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private static GrpcServerRunner runnerOnEphemeralPort() {
        return new GrpcServerRunner(0, new TenantPermissionsGrpcService(noopService()));
    }

    @Test
    void disabledLifecycleNeverStartsTheServer() {
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(false, runnerOnEphemeralPort());

        lifecycle.start();

        assertFalse(lifecycle.isRunning());
        assertEquals(-1, lifecycle.getPort());
    }

    @Test
    void startAndStopAreIdempotent() {
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(true, runnerOnEphemeralPort());
        try {
            lifecycle.start();
            int port = lifecycle.getPort();
            lifecycle.start();

            assertTrue(lifecycle.isRunning());
            assertEquals(port, lifecycle.getPort(), "a second start must not rebind");
        } finally {
            lifecycle.stop();
            lifecycle.stop();
        }
        assertFalse(lifecycle.isRunning());
        assertEquals(-1, lifecycle.getPort());
    }

    @Test
    void outOfRangePortIsRejectedAtConstruction() {
        TenantPermissionsGrpcService service = new TenantPermissionsGrpcService(noopService());

        assertThrows(IllegalArgumentException.class, () -> new GrpcServerRunner(-1, service));
        assertThrows(IllegalArgumentException.class, () -> new GrpcServerRunner(65536, service));
    }
}
