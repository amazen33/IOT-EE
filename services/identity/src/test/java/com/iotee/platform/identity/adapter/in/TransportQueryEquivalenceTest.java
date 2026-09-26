package com.iotee.platform.identity.adapter.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iotee.platform.contracts.identity.v1.GetTenantPermissionsRequest;
import com.iotee.platform.contracts.identity.v1.GetTenantPermissionsResponse;
import com.iotee.platform.contracts.identity.v1.TenantPermissionsServiceGrpc;
import com.iotee.platform.identity.adapter.in.grpc.BearerTokenServerInterceptor;
import com.iotee.platform.identity.adapter.in.grpc.TenantPermissionsGrpcService;
import com.iotee.platform.identity.adapter.in.rest.RestExceptionAdvice;
import com.iotee.platform.identity.adapter.in.rest.TenantPermissionsController;
import com.iotee.platform.identity.port.in.GetTenantPermissionsQuery;
import com.iotee.platform.identity.port.in.GetTenantPermissionsUseCase;
import com.iotee.platform.identity.port.in.InvalidQueryException;
import com.iotee.platform.identity.port.in.TenantPermissionsView;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Command/query-equivalence suite for ADR 0017 Decision 3 ("dual transport
 * through shared inbound ports"): an equivalent payload -- now including
 * the bearer token -- sent over REST and over gRPC must reach the inbound
 * port as the IDENTICAL {@link GetTenantPermissionsQuery}, and the port's
 * answer must come back identically on both wires.
 *
 * <p>Each transport gets its own {@link RecordingUseCase} standing in for
 * the application layer, so the assertion is about the adapters' wire
 * mapping alone -- not about authentication or authorization, which the
 * application layer does once for both (see
 * {@code application.GetTenantPermissionsServiceTest}). Both transports
 * run for real: Spring MVC dispatch via a standalone {@link MockMvc}
 * (path-variable, query-parameter and header binding, JSON serialization,
 * exception advice), and gRPC via an in-process server (protobuf
 * serialization, stub dispatch, {@link BearerTokenServerInterceptor},
 * status mapping).
 */
class TransportQueryEquivalenceTest {

    /** Stands in for the application layer: records every query it receives. */
    private static final class RecordingUseCase implements GetTenantPermissionsUseCase {

        private final List<GetTenantPermissionsQuery> received = new CopyOnWriteArrayList<>();
        private final boolean reject;

        RecordingUseCase(boolean reject) {
            this.reject = reject;
        }

        @Override
        public TenantPermissionsView getTenantPermissions(GetTenantPermissionsQuery query) {
            received.add(query);
            if (reject) {
                throw new InvalidQueryException("rejected by test", new IllegalArgumentException("internal detail"));
            }
            return new TenantPermissionsView(query.tenantId(), query.subjectId(), List.of("TENANT_MANAGE", "TENANT_READ"));
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<Server> servers = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    private RecordingUseCase restPort;
    private RecordingUseCase grpcPort;
    private MockMvc rest;
    private TenantPermissionsServiceGrpc.TenantPermissionsServiceBlockingStub grpc;

    @BeforeEach
    void wireBothTransports() throws IOException {
        wire(false);
    }

    private void wire(boolean reject) throws IOException {
        restPort = new RecordingUseCase(reject);
        grpcPort = new RecordingUseCase(reject);

        rest = MockMvcBuilders.standaloneSetup(new TenantPermissionsController(restPort))
                .setControllerAdvice(new RestExceptionAdvice())
                .build();

        String name = InProcessServerBuilder.generateName();
        servers.add(InProcessServerBuilder.forName(name).directExecutor()
                .addService(new TenantPermissionsGrpcService(grpcPort))
                .intercept(new BearerTokenServerInterceptor())
                .build().start());
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        channels.add(channel);
        grpc = TenantPermissionsServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void shutDown() {
        channels.forEach(ManagedChannel::shutdownNow);
        servers.forEach(Server::shutdownNow);
    }

    static Stream<Arguments> equivalentPayloads() {
        return Stream.of(
                Arguments.of("synthetic-tenant-acme-001", "synthetic-subject-admin", "token-a"),
                Arguments.of("synthetic-tenant-acme-001", "synthetic-subject-viewer", "token-b"),
                // Domain-invalid tenant: the adapters must pass it through
                // untouched; rejecting it is the application layer's job.
                Arguments.of("tenant-prod-4471", "synthetic-subject-admin", "token-c"),
                // Characters that need care on each wire (query-string
                // escaping on REST, UTF-8 on protobuf).
                Arguments.of("synthetic-tenant-acme-001", "subject with spaces+plus&amp=eq", "token-d"),
                // Non-ASCII written as escapes: repository sources stay ASCII-only.
                Arguments.of("synthetic-tenant-acme-001", "sübjéct-ünïcødé-✓", "token-e"),
                // Empty subject: REST "?subjectId=" and an unset proto3
                // field must arrive as the same empty string.
                Arguments.of("synthetic-tenant-acme-001", "", "token-f"),
                // No token at all: both adapters must produce "" (never null), never a header
                // that happened to be missing turning into some other sentinel.
                Arguments.of("synthetic-tenant-acme-001", "synthetic-subject-admin", ""));
    }

    private MvcResult sendRest(String tenantId, String subjectId, String bearerToken, int expectedStatus) throws Exception {
        var requestBuilder = get("/tenants/{tenantId}/permissions", tenantId).param("subjectId", subjectId);
        if (!bearerToken.isEmpty()) {
            requestBuilder = requestBuilder.header("Authorization", "Bearer " + bearerToken);
        }
        return rest.perform(requestBuilder).andExpect(status().is(expectedStatus)).andReturn();
    }

    private static GetTenantPermissionsRequest grpcRequest(String tenantId, String subjectId) {
        GetTenantPermissionsRequest.Builder builder = GetTenantPermissionsRequest.newBuilder().setTenantId(tenantId);
        if (!subjectId.isEmpty()) {
            builder.setSubjectId(subjectId); // leave "" unset: exercises proto3's default
        }
        return builder.build();
    }

    private TenantPermissionsServiceGrpc.TenantPermissionsServiceBlockingStub grpcWithToken(String bearerToken) {
        if (bearerToken.isEmpty()) {
            return grpc;
        }
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + bearerToken);
        return grpc.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    @ParameterizedTest
    @MethodSource("equivalentPayloads")
    void equivalentPayloadsReachTheInboundPortAsIdenticalQueries(String tenantId, String subjectId, String bearerToken)
            throws Exception {
        sendRest(tenantId, subjectId, bearerToken, 200);
        grpcWithToken(bearerToken).getTenantPermissions(grpcRequest(tenantId, subjectId));

        GetTenantPermissionsQuery expected = new GetTenantPermissionsQuery(tenantId, subjectId, bearerToken);
        assertEquals(List.of(expected), restPort.received, "REST adapter must call the port exactly once, verbatim");
        assertEquals(List.of(expected), grpcPort.received, "gRPC adapter must call the port exactly once, verbatim");
        assertEquals(restPort.received, grpcPort.received, "both transports must produce the identical query");
    }

    @ParameterizedTest
    @MethodSource("equivalentPayloads")
    void thePortsAnswerComesBackIdenticallyOnBothWires(String tenantId, String subjectId, String bearerToken) throws Exception {
        JsonNode restBody = json.readTree(
                sendRest(tenantId, subjectId, bearerToken, 200).getResponse().getContentAsString(StandardCharsets.UTF_8));
        GetTenantPermissionsResponse grpcBody = grpcWithToken(bearerToken).getTenantPermissions(grpcRequest(tenantId, subjectId));

        assertEquals(restBody.get("tenantId").asText(), grpcBody.getTenantId());
        assertEquals(restBody.get("subjectId").asText(), grpcBody.getSubjectId());
        List<String> restPermissions = new ArrayList<>();
        restBody.get("permissions").forEach(node -> restPermissions.add(node.asText()));
        assertEquals(restPermissions, grpcBody.getPermissionsList(), "same permissions, same order");
    }

    @Test
    void aPortRejectionMapsToEachTransportsInvalidArgumentWithTheSameClientSafeText() throws Exception {
        shutDown();
        wire(true);

        JsonNode restBody = json.readTree(sendRest("synthetic-tenant-acme-001", "synthetic-subject-admin", "token-g", 400)
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        StatusRuntimeException grpcError = assertThrows(StatusRuntimeException.class, () -> grpcWithToken("token-g")
                .getTenantPermissions(grpcRequest("synthetic-tenant-acme-001", "synthetic-subject-admin")));

        assertEquals(Status.Code.INVALID_ARGUMENT, grpcError.getStatus().getCode());
        assertEquals(restBody.get("detail").asText(), grpcError.getStatus().getDescription(),
                "both transports return the same fixed, client-safe text");
        assertEquals(restPort.received, grpcPort.received, "rejected requests were still identical queries");
    }
}
