package com.iotee.platform.identity.config;

import com.iotee.platform.identity.adapter.in.grpc.GrpcServerRunner;
import com.iotee.platform.identity.adapter.in.grpc.TenantPermissionsGrpcService;
import com.iotee.platform.identity.adapter.out.persistence.InMemoryRoleAssignmentRepository;
import com.iotee.platform.identity.application.GetTenantPermissionsService;
import com.iotee.platform.identity.port.in.GetTenantPermissionsUseCase;
import com.iotee.platform.identity.port.out.RoleAssignmentRepository;
import com.iotee.platform.identity.rbac.AbacContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * type (ADR 0017 Decision 4).
 */
@Configuration
public class IdentityServiceConfig {

    @Bean
    public RoleAssignmentRepository roleAssignmentRepository() {
        return InMemoryRoleAssignmentRepository.withSyntheticSeed();
    }

    /** Still the walking-skeleton stub; a real ABAC engine replaces it without touching any caller. */
    @Bean
    public AbacContext abacContext() {
        return AbacContext.alwaysPermit();
    }

    @Bean
    public GetTenantPermissionsUseCase getTenantPermissionsUseCase(
            RoleAssignmentRepository roleAssignmentRepository, AbacContext abacContext) {
        return new GetTenantPermissionsService(roleAssignmentRepository, abacContext);
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
