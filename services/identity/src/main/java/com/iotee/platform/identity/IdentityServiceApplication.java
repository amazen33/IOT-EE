package com.iotee.platform.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for {@code services/identity}, the M9 walking
 * skeleton (ADR 0012 Decision 6) rebuilt per ADR 0013 and laid out per
 * ADR 0017 (Track C step C1):
 *
 * <ul>
 *   <li>{@code domain}, {@code rbac} -- framework-free business rules;</li>
 *   <li>{@code port.in} / {@code port.out} -- inbound use-case and
 *       outbound dependency interfaces;</li>
 *   <li>{@code application} -- implements the inbound ports;</li>
 *   <li>{@code adapter.in.rest}, {@code adapter.in.grpc} -- two driving
 *       adapters over the SAME inbound port;</li>
 *   <li>{@code adapter.out.persistence} -- in-memory stand-in for the
 *       future Postgres adapter;</li>
 *   <li>{@code config} -- the composition root
 *       ({@link com.iotee.platform.identity.config.IdentityServiceConfig});</li>
 *   <li>{@code correlation} -- framework-free correlation-ID context.</li>
 * </ul>
 *
 * <p>Dependency direction is enforced by
 * {@code IdentityHexagonalArchitectureRulesTest}. See this module's
 * README.md for scope and verification status.
 */
@SpringBootApplication
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
