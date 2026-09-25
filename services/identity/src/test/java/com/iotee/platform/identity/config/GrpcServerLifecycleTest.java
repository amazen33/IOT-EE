package com.iotee.platform.identity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iotee.platform.identity.adapter.in.grpc.GrpcServerRunner;
import com.iotee.platform.identity.adapter.in.grpc.TenantPermissionsGrpcService;
import com.iotee.platform.identity.adapter.out.persistence.InMemoryRoleAssignmentRepository;
import com.iotee.platform.identity.application.GetTenantPermissionsService;
import com.iotee.platform.identity.rbac.AbacContext;
import org.junit.jupiter.api.Test;

/** Lifecycle edge cases for the gRPC server, without a Spring context. */
class GrpcServerLifecycleTest {

    private static GrpcServerRunner runnerOnEphemeralPort() {
        return new GrpcServerRunner(0, new TenantPermissionsGrpcService(new GetTenantPermissionsService(
                InMemoryRoleAssignmentRepository.withSyntheticSeed(), AbacContext.alwaysPermit())));
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
        TenantPermissionsGrpcService service = new TenantPermissionsGrpcService(new GetTenantPermissionsService(
                InMemoryRoleAssignmentRepository.withSyntheticSeed(), AbacContext.alwaysPermit()));

        assertThrows(IllegalArgumentException.class, () -> new GrpcServerRunner(-1, service));
        assertThrows(IllegalArgumentException.class, () -> new GrpcServerRunner(65536, service));
    }
}
