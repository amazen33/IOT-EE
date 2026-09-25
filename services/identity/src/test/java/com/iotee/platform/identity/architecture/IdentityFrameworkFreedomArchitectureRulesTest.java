package com.iotee.platform.identity.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iotee.platform.architecture.ArchRules;
import com.iotee.platform.architecture.ImportHelper;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * Reintroduces the two ArchUnit denylists that Track B's original
 * {@code common} module carried (ADR 0012 Risk 2 / the pre-refactor
 * {@code CommonArchitectureRulesTest} and
 * {@code CommonFrameworkFreedomArchitectureRulesTest}) but that were
 * dropped, not carried forward, when {@code common} was retired in the
 * ADR 0013 autonomy refactor (a450156). Their intent -- a mechanical
 * boundary at every vendor-SDK and persistence-provider seam named in
 * ADR 0011 Decision 3 -- is unchanged; only the home module changed,
 * from a shared {@code common} module to this service's own build,
 * per ADR 0013's accepted-duplication model (a future second service
 * declares the equivalent rules against its own package names, the
 * same way {@code IdentityArchitectureRulesTest} does for the other
 * four rules).
 *
 * <p>Two rules:
 *
 * <ol>
 *   <li>No class in {@code services.identity}, outside the driven-
 *       adapter ring {@code services.identity.adapter.out..} (ADR 0017
 *       Decision 2), may depend on a cloud-vendor, messaging, CDC, or
 *       ThingsBoard SDK ({@code software.amazon.awssdk..},
 *       {@code io.minio..}, {@code org.apache.kafka..},
 *       {@code io.debezium..}, {@code org.thingsboard..}). The
 *       {@code adapter.out} ring exists since Track C step C1 (the
 *       in-memory role-assignment adapter), but nothing in it uses a
 *       vendor SDK yet, so the exemption is still VACUOUS -- see
 *       {@link #noVendorSdkOutsideAdaptersRuleIsCurrentlyVacuousForItsExemption()},
 *       which fails the day the first real vendor-backed adapter lands.
 *       (ADR 0017 Decision 4 narrows this further, per concern --
 *       e.g. Kafka only in {@code adapter.out.messaging} -- when those
 *       adapters exist.)</li>
 *   <li>No class in {@code services.identity} may depend on a
 *       persistence PROVIDER ({@code org.hibernate..}, {@code
 *       org.eclipse.persistence..}, {@code org.jooq..}). The JPA spec
 *       itself ({@code jakarta.persistence..}) is allowed but unused --
 *       Track B carries no persistence code (ADR 0012 non-goals,
 *       unchanged).</li>
 * </ol>
 *
 * <p>Both rules are built from {@link ArchRules}, not hand-rolled, and
 * both follow the same two-scan discipline as {@code
 * IdentityArchitectureRulesTest}: a "holds for real code" check via
 * {@link ImportHelper#importProductionClasses}, and a negative test
 * proving the rule fires via {@link ImportHelper#importFixturePackages}
 * against a stub declared under the real denylisted package name (a
 * package-name string match, not a "came from the real jar" check --
 * see e.g. {@code FakeKafkaProducerClass}'s Javadoc).
 */
class IdentityFrameworkFreedomArchitectureRulesTest {

    private static final String BASE_PACKAGE = "com.iotee.platform.identity";

    // Denylist per ADR 0011 Decision 3 / ADR 0012 Risk 2's own list;
    // extend as needed when a new vendor SDK or client library is
    // introduced anywhere in the platform.
    private static final String[] VENDOR_SDK_DENYLIST = {
            "software.amazon.awssdk..",
            "io.minio..",
            "org.apache.kafka..",
            "io.debezium..",
            // ThingsBoard's real Java/Maven namespace is org.thingsboard
            // (e.g. org.thingsboard.server..), not com.thingsboard.
            "org.thingsboard.."
    };

    private static final String[] PERSISTENCE_PROVIDER_DENYLIST = {
            "org.hibernate..",
            "org.eclipse.persistence..",
            "org.jooq.."
    };

    private static JavaClasses importIdentityModule() {
        return ImportHelper.importProductionClasses(BASE_PACKAGE);
    }

    private static ArchRule noVendorSdkOutsideAdapters() {
        return ArchRules.noClassesOutsideSubpackageDependOnPackages(
                BASE_PACKAGE,
                BASE_PACKAGE + ".adapter.out..",
                "vendor SDKs (AWS SDK, MinIO, Kafka, Debezium, ThingsBoard) are confined to "
                        + "the driven-adapter ring services.identity.adapter.out (ADR 0017 "
                        + "Decision 2/4); the domain, ports, application layer, and driving "
                        + "adapters must stay vendor-neutral.",
                VENDOR_SDK_DENYLIST);
    }

    private static ArchRule noPersistenceProvider() {
        return ArchRules.noClassesDependOnPackages(
                BASE_PACKAGE,
                "jakarta.persistence (the JPA spec) is allowed in services.identity, but a JPA "
                        + "PROVIDER (Hibernate, EclipseLink, jOOQ) is not the API and stays confined "
                        + "to a future persistence adapter module (M10+; Track B adds no persistence "
                        + "code).",
                PERSISTENCE_PROVIDER_DENYLIST);
    }

    @Test
    void identityDoesNotDependOnAVendorSdkOutsideAdapters() {
        noVendorSdkOutsideAdapters().check(importIdentityModule());
    }

    @Test
    void noVendorSdkOutsideAdaptersRuleIsCurrentlyVacuousForItsExemption() {
        // The adapter.out ring exists (step C1's in-memory adapter), but no
        // class in it depends on a vendor SDK yet, so the exemption has
        // never actually exempted anything. This test fails the day a real
        // vendor-backed adapter lands, forcing this class's Javadoc to say
        // the exemption is now exercised for real.
        JavaClasses classes = importIdentityModule();
        String adapterOutPrefix = BASE_PACKAGE + ".adapter.out";
        boolean anyAdapterOutClassUsesAVendorSdk = classes.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith(adapterOutPrefix))
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .map(dependency -> dependency.getTargetClass().getPackageName())
                .anyMatch(targetPackage -> {
                    for (String denied : VENDOR_SDK_DENYLIST) {
                        String root = denied.substring(0, denied.length() - 2);
                        if (targetPackage.equals(root) || targetPackage.startsWith(root + ".")) {
                            return true;
                        }
                    }
                    return false;
                });
        assertFalse(anyAdapterOutClassUsesAVendorSdk,
                "expected no vendor SDK use inside services.identity.adapter.out yet -- if this now "
                        + "fails, the vendor-SDK exemption is no longer vacuous and this test (and the "
                        + "class Javadoc) should be updated to say so");
    }

    @Test
    void noVendorSdkOutsideAdaptersRuleActuallyCatchesAViolation() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.architecture.fixtures",
                "org.apache.kafka");

        EvaluationResult result = noVendorSdkOutsideAdapters().evaluate(fixtureClasses);

        assertFalse(result.getFailureReport().isEmpty(),
                "the fixture class deliberately depends on org.apache.kafka; if this is empty, "
                        + "the no-vendor-SDK-outside-adapters rule is not actually being enforced");
        assertTrue(
                result.getFailureReport().toString().contains("org.apache.kafka"),
                () -> "expected the failure report to name org.apache.kafka, got: "
                        + result.getFailureReport());
    }

    @Test
    void theVendorSdkFixtureViolationWouldFailIfRunAsARealAssertion() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.architecture.fixtures",
                "org.apache.kafka");

        assertThrows(AssertionError.class, () -> noVendorSdkOutsideAdapters().check(fixtureClasses));
    }

    @Test
    void identityDoesNotDependOnAPersistenceProvider() {
        noPersistenceProvider().check(importIdentityModule());
    }

    @Test
    void persistenceProviderRuleActuallyCatchesAViolation() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.architecture.fixtures",
                "org.hibernate",
                "org.eclipse.persistence",
                "org.jooq");

        EvaluationResult result = noPersistenceProvider().evaluate(fixtureClasses);

        assertFalse(result.getFailureReport().isEmpty(),
                "the fixture class deliberately depends on Hibernate/EclipseLink/jOOQ; if this is "
                        + "empty, the persistence-provider rule is not actually being enforced");
        String report = result.getFailureReport().toString();
        assertTrue(report.contains("org.hibernate"),
                () -> "expected the failure report to name org.hibernate, got: " + report);
        assertTrue(report.contains("org.eclipse.persistence"),
                () -> "expected the failure report to name org.eclipse.persistence, got: " + report);
        assertTrue(report.contains("org.jooq"),
                () -> "expected the failure report to name org.jooq, got: " + report);
    }

    @Test
    void thePersistenceProviderFixtureViolationWouldFailIfRunAsARealAssertion() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.architecture.fixtures",
                "org.hibernate",
                "org.eclipse.persistence",
                "org.jooq");

        assertThrows(AssertionError.class, () -> noPersistenceProvider().check(fixtureClasses));
    }
}
