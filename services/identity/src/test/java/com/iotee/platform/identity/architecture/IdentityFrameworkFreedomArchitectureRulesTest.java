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
 *   <li>No class in {@code services.identity}, outside a future {@code
 *       services.identity.adapters} package, may depend on a
 *       cloud-vendor or messaging SDK ({@code software.amazon.awssdk..},
 *       {@code io.minio..}, {@code org.apache.kafka..}). Currently
 *       VACUOUSLY exempt-package-wise, since no {@code adapters}
 *       package exists in this service yet (M10+; mirrors the Python
 *       spec's {@code adapters/worm_s3} boundary) -- the exemption is
 *       declared now so the rule does not need rewriting the day that
 *       package is added, matching ADR 0012 Risk 2's own vacuous-until-
 *       exercised framing for this exact rule shape.</li>
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
            "org.apache.kafka.."
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
                BASE_PACKAGE + ".adapters",
                "vendor SDKs (AWS SDK, MinIO, Kafka clients) are confined to a future "
                        + "services.identity.adapters package (mirrors the Python spec's "
                        + "adapters/worm_s3 boundary; no such package exists in this service yet, "
                        + "so this rule's exemption is currently vacuous); the rest of "
                        + "services.identity must stay vendor-neutral.",
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
        // No services.identity.adapters package exists yet -- this test
        // fails, forcing this class's Javadoc to be revisited, the day one
        // is added, since the exemption would then need to be exercised
        // for real rather than assumed vacuous.
        JavaClasses classes = importIdentityModule();
        boolean anyAdaptersPackageExists =
                classes.stream().anyMatch(javaClass -> javaClass.getPackageName().contains(".adapters"));
        assertFalse(anyAdaptersPackageExists,
                "expected no services.identity.adapters package yet -- if this now fails, the "
                        + "vendor-SDK-outside-adapters exemption is no longer vacuous and this test "
                        + "(and the class Javadoc) should be updated to say so");
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
