package com.iotee.platform.identity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.iotee.platform.contracts.identity.v1.GetTenantPermissionsRequest;
import com.iotee.platform.contracts.identity.v1.TenantPermissionsServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end wiring through the real Spring context: the composition
 * root starts a real gRPC server (Netty transport, ephemeral port), and
 * an unauthenticated request over REST and over a real gRPC network
 * channel is rejected identically -- both transports reach the SAME real
 * {@code application.GetTenantPermissionsService}, wired with the SAME
 * real {@code adapter.out.jwt.NimbusTokenSignatureVerifier} (pointed at a
 * placeholder JWKS URI, since no real identity provider is deployed yet).
 *
 * <p>This class cannot exercise the 200 path (that needs a real, validly
 * signed token from a real or realistic-fake issuer, which does not exist
 * in this environment); {@code application.GetTenantPermissionsServiceTest}
 * proves the 200 path, without Spring, with a fake verifier. What THIS
 * class proves is that the real composition root -- not a test double --
 * rejects an unauthenticated request on both live transports, the same
 * way, end to end.
 */
@SpringBootTest(properties = "iotee.identity.grpc.port=0")
@AutoConfigureMockMvc
class GrpcServerWiringTest {

    @Autowired
    private GrpcServerLifecycle grpcServer;

    @Autowired
    private MockMvc mockMvc;

    private ManagedChannel channel;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void theContextStartsTheGrpcServerOnAnEphemeralPort() {
        assertTrue(grpcServer.isRunning());
        assertTrue(grpcServer.getPort() > 0, "port 0 must resolve to a real bound port");
    }

    @Test
    void anUnauthenticatedRequestIsRejectedIdenticallyOnBothLiveTransports() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", "synthetic-tenant-acme-001")
                        .param("subjectId", "synthetic-subject-admin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());

        StatusRuntimeException grpcError = assertThrows(StatusRuntimeException.class,
                () -> TenantPermissionsServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .getTenantPermissions(GetTenantPermissionsRequest.newBuilder()
                                .setTenantId("synthetic-tenant-acme-001")
                                .setSubjectId("synthetic-subject-admin")
                                .build()));

        assertEquals(Status.Code.UNAUTHENTICATED, grpcError.getStatus().getCode());
    }
}
