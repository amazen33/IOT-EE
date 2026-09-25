package com.iotee.platform.identity.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iotee.platform.architecture.ArchRules;
import com.iotee.platform.architecture.ImportHelper;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hexagonal dependency-direction gates for {@code services.identity}
 * (ADR 0017 Decisions 1-4; Track C step C1). Built from the shared
 * {@code ArchRules} factories with this service's own package names (ADR
 * 0013 Decision 8), and following this platform's two-scan discipline:
 * every rule is checked against the real production classes AND proven
 * to fire against a deliberately violating fixture.
 *
 * <p>Rings, inner to outer: {@code domain} + {@code rbac} (business
 * rules) -> {@code port.in} / {@code port.out} -> {@code application} ->
 * {@code adapter.*} -> {@code config} (composition root). Dependencies
 * point inward only. {@code correlation} is a framework-free,
 * transport-neutral utility that driving adapters may use alongside
 * {@code port.in}.
 *
 * <ol>
 *   <li>The REST adapter depends, inside this service, only on
 *       {@code port.in} (plus {@code correlation}): never on the domain,
 *       {@code rbac}, the application layer, {@code port.out}, a driven
 *       adapter, {@code config}, or the gRPC adapter and its wire
 *       types.</li>
 *   <li>The gRPC adapter, symmetrically: never on the domain,
 *       {@code rbac}, the application layer, {@code port.out}, a driven
 *       adapter, {@code config}, or the REST adapter -- and never on
 *       Spring.</li>
 *   <li>{@code domain} and {@code rbac} have no outgoing dependency on
 *       any outer ring or on any transport/framework library.</li>
 *   <li>{@code port.*} never depends on the application layer, adapters,
 *       {@code config}, or transport/framework libraries.</li>
 *   <li>{@code application} reaches driven adapters only through
 *       {@code port.out}: never on an adapter, {@code config}, or a
 *       transport/framework library.</li>
 *   <li>ADR 0017 Decision 4: {@code io.grpc..} only inside
 *       {@code adapter.in.grpc}; {@code org.springframework.web..} only
 *       inside {@code adapter.in.rest}.</li>
 * </ol>
 */
class IdentityHexagonalArchitectureRulesTest {

    private static final String BASE = "com.iotee.platform.identity";

    private static final String DOMAIN = BASE + ".domain..";
    private static final String RBAC = BASE + ".rbac..";
    private static final String PORT = BASE + ".port..";
    private static final String PORT_OUT = BASE + ".port.out..";
    private static final String APPLICATION = BASE + ".application..";
    private static final String ADAPTER = BASE + ".adapter..";
    private static final String ADAPTER_OUT = BASE + ".adapter.out..";
    private static final String REST_ADAPTER = BASE + ".adapter.in.rest..";
    private static final String GRPC_ADAPTER = BASE + ".adapter.in.grpc..";
    private static final String CONFIG = BASE + ".config..";

    /** The identity gRPC wire types generated from contracts/identity/v1. */
    private static final String GRPC_WIRE_TYPES = "com.iotee.platform.contracts.identity..";

    /** Transport and framework libraries the inside of the hexagon must never see. */
    private static final String[] TRANSPORT_AND_FRAMEWORKS = {
            "org.springframework..", "jakarta.servlet..", "jakarta.ws.rs..",
            "io.grpc..", "com.google.protobuf..", "com.iotee.platform.contracts.."
    };

    private static JavaClasses production() {
        return ImportHelper.importProductionClasses(BASE);
    }

    private static String[] concat(String[] first, String... rest) {
        String[] all = new String[first.length + rest.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(rest, 0, all, first.length, rest.length);
        return all;
    }

    // ---------------------------------------------------------------- rules

    private static ArchRule restAdapterDependsOnlyOnInboundPorts() {
        return ArchRules.noClassesDependOnPackages(
                BASE + ".adapter.in.rest",
                "the REST driving adapter translates HTTP into port.in queries and nothing else "
                        + "(ADR 0017 Decisions 2-3); it never reaches past the inbound port, and never "
                        + "into the gRPC adapter or its wire types",
                DOMAIN, RBAC, APPLICATION, PORT_OUT, ADAPTER_OUT, CONFIG, GRPC_ADAPTER, GRPC_WIRE_TYPES, "io.grpc..");
    }

    private static ArchRule grpcAdapterDependsOnlyOnInboundPorts() {
        return ArchRules.noClassesDependOnPackages(
                BASE + ".adapter.in.grpc",
                "the gRPC driving adapter translates protobuf into port.in queries and nothing else "
                        + "(ADR 0017 Decisions 2-3); it never reaches past the inbound port, never into "
                        + "the REST adapter, and stays free of Spring",
                DOMAIN, RBAC, APPLICATION, PORT_OUT, ADAPTER_OUT, CONFIG, REST_ADAPTER, "org.springframework..");
    }

    private static ArchRule innerRingMustNotDependOnOuterRings(String innerPackage) {
        return ArchRules.noClassesDependOnPackages(
                BASE + "." + innerPackage,
                "services.identity." + innerPackage + " is the innermost ring (ADR 0017 Decision 1): "
                        + "no dependency on ports, the application layer, adapters, config, or any "
                        + "transport/framework library",
                concat(TRANSPORT_AND_FRAMEWORKS, PORT, APPLICATION, ADAPTER, CONFIG));
    }

    private static ArchRule portsMustNotDependOnOuterRings() {
        return ArchRules.noClassesDependOnPackages(
                BASE + ".port",
                "ports are plain interfaces and records (ADR 0017 Decision 2); they may use the "
                        + "domain ring and nothing outside it",
                concat(TRANSPORT_AND_FRAMEWORKS, APPLICATION, ADAPTER, CONFIG));
    }

    private static ArchRule applicationReachesAdaptersOnlyThroughPorts() {
        return ArchRules.noClassesDependOnPackages(
                BASE + ".application",
                "the application layer implements port.in and calls port.out; it never names a "
                        + "concrete adapter, the composition root, or a transport/framework library "
                        + "(ADR 0017 Decisions 1-2)",
                concat(TRANSPORT_AND_FRAMEWORKS, ADAPTER, CONFIG));
    }

    private static ArchRule grpcConfinedToGrpcAdapter() {
        return ArchRules.noClassesOutsideSubpackageDependOnPackages(
                BASE, GRPC_ADAPTER,
                "ADR 0017 Decision 4: io.grpc is used only inside adapter.in.grpc",
                "io.grpc..");
    }

    private static ArchRule springWebConfinedToRestAdapter() {
        return ArchRules.noClassesOutsideSubpackageDependOnPackages(
                BASE, REST_ADAPTER,
                "ADR 0017 Decision 4: org.springframework.web is used only inside adapter.in.rest",
                "org.springframework.web..");
    }

    // ------------------------------------------------- holds for real code

    @Test
    void everyRingTheseRulesProtectActuallyContainsProductionClasses() {
        // Guards against a vacuous pass: a renamed package would otherwise
        // leave a rule with nothing to check.
        JavaClasses classes = production();
        for (String ring : List.of("domain", "rbac", "port.in", "port.out", "application",
                "adapter.in.rest", "adapter.in.grpc", "adapter.out.persistence", "config")) {
            String packageName = BASE + "." + ring;
            assertTrue(classes.stream().anyMatch(c -> c.getPackageName().equals(packageName)),
                    () -> "expected production classes in " + packageName);
        }
    }

    @Test
    void restAdapterHoldsForRealCode() {
        restAdapterDependsOnlyOnInboundPorts().check(production());
    }

    @Test
    void grpcAdapterHoldsForRealCode() {
        grpcAdapterDependsOnlyOnInboundPorts().check(production());
    }

    @Test
    void domainHoldsForRealCode() {
        innerRingMustNotDependOnOuterRings("domain").check(production());
    }

    @Test
    void rbacHoldsForRealCode() {
        innerRingMustNotDependOnOuterRings("rbac").check(production());
    }

    @Test
    void portsHoldForRealCode() {
        portsMustNotDependOnOuterRings().check(production());
    }

    @Test
    void applicationHoldsForRealCode() {
        applicationReachesAdaptersOnlyThroughPorts().check(production());
    }

    @Test
    void grpcConfinementHoldsForRealCode() {
        grpcConfinedToGrpcAdapter().check(production());
    }

    @Test
    void springWebConfinementHoldsForRealCode() {
        springWebConfinedToRestAdapter().check(production());
    }

    // ------------------------------------------ each rule actually fires

    private static void assertRuleFires(ArchRule rule, String expectedInReport, String... fixturePackages) {
        JavaClasses fixtures = ImportHelper.importFixturePackages(fixturePackages);
        EvaluationResult result = rule.evaluate(fixtures);
        assertFalse(result.getFailureReport().isEmpty(),
                () -> "the fixture deliberately violates this rule; an empty report means the rule "
                        + "is not being enforced: " + rule.getDescription());
        String report = result.getFailureReport().toString();
        assertTrue(report.contains(expectedInReport),
                () -> "expected the failure report to name " + expectedInReport + ", got: " + report);
        assertThrows(AssertionError.class, () -> rule.check(fixtures));
    }

    @Test
    void restAdapterRuleCatchesADomainShortcut() {
        assertRuleFires(restAdapterDependsOnlyOnInboundPorts(), BASE + ".domain.TenantId",
                BASE + ".adapter.in.rest.fixtures", BASE + ".domain");
    }

    @Test
    void restAdapterRuleCatchesADependencyOnTheGrpcAdapter() {
        assertRuleFires(restAdapterDependsOnlyOnInboundPorts(), BASE + ".adapter.in.grpc.TenantPermissionsGrpcService",
                BASE + ".adapter.in.rest.fixtures", BASE + ".adapter.in.grpc");
    }

    @Test
    void grpcAdapterRuleCatchesADomainShortcut() {
        assertRuleFires(grpcAdapterDependsOnlyOnInboundPorts(), BASE + ".domain.TenantId",
                BASE + ".adapter.in.grpc.fixtures", BASE + ".domain");
    }

    @Test
    void grpcAdapterRuleCatchesADependencyOnTheRestAdapter() {
        assertRuleFires(grpcAdapterDependsOnlyOnInboundPorts(), BASE + ".adapter.in.rest.TenantPermissionsController",
                BASE + ".adapter.in.grpc.fixtures", BASE + ".adapter.in.rest");
    }

    @Test
    void domainRuleCatchesAnOutwardDependency() {
        assertRuleFires(innerRingMustNotDependOnOuterRings("domain"), BASE + ".port.in.GetTenantPermissionsUseCase",
                BASE + ".domain.fixtures.outward", BASE + ".port.in", BASE + ".adapter.out.persistence");
    }

    @Test
    void portRuleCatchesADependencyOnTheApplicationLayer() {
        assertRuleFires(portsMustNotDependOnOuterRings(), BASE + ".application.GetTenantPermissionsService",
                BASE + ".port.in.fixtures", BASE + ".application");
    }

    @Test
    void applicationRuleCatchesAConcreteAdapterDependency() {
        assertRuleFires(applicationReachesAdaptersOnlyThroughPorts(),
                BASE + ".adapter.out.persistence.InMemoryRoleAssignmentRepository",
                BASE + ".application.fixtures.outward", BASE + ".adapter.out.persistence");
    }

    @Test
    void grpcConfinementRuleCatchesGrpcOutsideTheGrpcAdapter() {
        assertRuleFires(grpcConfinedToGrpcAdapter(), "io.grpc.Status",
                BASE + ".architecture.fixtures.transport");
    }

    @Test
    void springWebConfinementRuleCatchesSpringWebOutsideTheRestAdapter() {
        // Reuses the domain framework-freedom fixture: a domain-resident
        // class annotated with a stand-in under org.springframework.web.
        assertRuleFires(springWebConfinedToRestAdapter(), "org.springframework.web",
                BASE + ".domain.fixtures", "org.springframework.web.bind.annotation");
    }
}
