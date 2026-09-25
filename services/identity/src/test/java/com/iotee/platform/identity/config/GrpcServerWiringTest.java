package com.iotee.platform.identity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iotee.platform.contracts.identity.v1.GetTenantPermissionsRequest;
import com.iotee.platform.contracts.identity.v1.GetTenantPermissionsResponse;
import com.iotee.platform.contracts.identity.v1.TenantPermissionsServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * the same request over REST and over a real gRPC network channel
 * returns the same answer from the same application service.
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
    void restAndGrpcReturnTheSameAnswerFromTheSameContext() throws Exception {
        String body = mockMvc.perform(get("/tenants/{tenantId}/permissions", "synthetic-tenant-acme-001")
                        .param("subjectId", "synthetic-subject-admin"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode restBody = new ObjectMapper().readTree(body);
        List<String> restPermissions = new ArrayList<>();
        restBody.get("permissions").forEach(node -> restPermissions.add(node.asText()));

        GetTenantPermissionsResponse grpcBody = TenantPermissionsServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .getTenantPermissions(GetTenantPermissionsRequest.newBuilder()
                        .setTenantId("synthetic-tenant-acme-001")
                        .setSubjectId("synthetic-subject-admin")
                        .build());

        assertEquals(List.of("TENANT_MANAGE", "TENANT_READ"), restPermissions);
        assertEquals(restPermissions, grpcBody.getPermissionsList());
        assertEquals(restBody.get("tenantId").asText(), grpcBody.getTenantId());
    }
}
