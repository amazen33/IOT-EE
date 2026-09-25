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
import com.iotee.platform.identity.rbac.AbacContext;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gRPC driving adapter over a real in-process gRPC transport (real
 * serialization and dispatch, no network port), backed by the real
 * application service and in-memory outbound adapter. Mirrors
 * {@code adapter.in.rest.TenantPermissionsControllerTest} case for case.
 */
class TenantPermissionsGrpcServiceTest {

    private static final String SYNTHETIC_TENANT = "synthetic-tenant-acme-001";

    private Server server;
    private ManagedChannel channel;
    private TenantPermissionsServiceGrpc.TenantPermissionsServiceBlockingStub stub;

    @BeforeEach
    void startInProcessServer() throws IOException {
        String name = InProcessServerBuilder.generateName();
        TenantPermissionsGrpcService adapter = new TenantPermissionsGrpcService(new GetTenantPermissionsService(
                InMemoryRoleAssignmentRepository.withSyntheticSeed(), AbacContext.alwaysPermit()));
        server = InProcessServerBuilder.forName(name).directExecutor().addService(adapter).build().start();
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

    @Test
    void adminSubjectReceivesBothPermissionsInAscendingOrder() {
        GetTenantPermissionsResponse response =
                stub.getTenantPermissions(request(SYNTHETIC_TENANT, "synthetic-subject-admin"));

        assertEquals(SYNTHETIC_TENANT, response.getTenantId());
        assertEquals("synthetic-subject-admin", response.getSubjectId());
        assertEquals(List.of("TENANT_MANAGE", "TENANT_READ"), response.getPermissionsList());
    }

    @Test
    void viewerSubjectReceivesOnlyReadPermission() {
        assertEquals(List.of("TENANT_READ"),
                stub.getTenantPermissions(request(SYNTHETIC_TENANT, "synthetic-subject-viewer")).getPermissionsList());
    }

    @Test
    void unassignedSubjectReceivesNoPermissions() {
        assertEquals(0, stub.getTenantPermissions(request(SYNTHETIC_TENANT, "synthetic-subject-unassigned"))
                .getPermissionsCount());
    }

    @Test
    void nonSyntheticTenantIdIsInvalidArgumentWithAFixedClientSafeDescription() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getTenantPermissions(request("tenant-prod-4471", "synthetic-subject-admin")));

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
        assertEquals(TenantPermissionsGrpcService.CLIENT_SAFE_DETAIL, ex.getStatus().getDescription());
        assertFalse(String.valueOf(ex.getStatus().getDescription()).contains("synthetic-tenant-"),
                "the tenant-id validation pattern is internal detail and must not reach the client");
    }

    @Test
    void emptySubjectIdIsInvalidArgument() {
        // proto3: an unset subject_id arrives as "", which the application
        // layer rejects -- the same outcome as REST's blank subjectId.
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getTenantPermissions(GetTenantPermissionsRequest.newBuilder()
                        .setTenantId(SYNTHETIC_TENANT).build()));

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    }

    @Test
    void toQueryCopiesBothWireFieldsVerbatim() {
        assertEquals(new GetTenantPermissionsQuery("t", "s"),
                TenantPermissionsGrpcService.toQuery(request("t", "s")));
    }

    @Test
    void toResponsePreservesPermissionOrder() {
        GetTenantPermissionsResponse response = TenantPermissionsGrpcService.toResponse(
                new TenantPermissionsView("t", "s", List.of("A", "B", "C")));

        assertEquals(List.of("A", "B", "C"), response.getPermissionsList());
    }
}
