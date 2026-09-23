package com.iotee.platform.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Walking-skeleton entry point (ADR 0012 Decision 6), rebuilt per ADR
 * 0013 (microservice autonomy and contract-based sharing). This first
 * commit intentionally does the minimum needed to prove the module
 * wiring works: one domain object
 * ({@link com.iotee.platform.identity.domain.Tenant}), one endpoint
 * split into a framework-free handler
 * ({@link com.iotee.platform.identity.core.TenantPermissionsHandler})
 * and a thin Spring adapter
 * ({@link com.iotee.platform.identity.web.TenantPermissionsController}),
 * this service's OWN RBAC primitive
 * ({@link com.iotee.platform.identity.rbac.RbacRegistry}) consulted for
 * real, and the correlation-ID interceptor registered by this service's
 * own {@link com.iotee.platform.identity.web.WebMvcConfig} -- no shared
 * auto-configuration module involved; ADR 0013 retired that shared
 * wiring module, and this service now wires its own interceptor
 * directly, the way any ordinary Spring Boot application does. See this
 * module's README.md for the full list of what is and is not in scope
 * yet.
 */
@SpringBootApplication
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
