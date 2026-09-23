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
 * ArchUnit boundary gates for {@code services.identity} (ADR 0012
 * Decision 6, ADR 0013 Decisions 1/2/5/6/8). Every rule here is built
 * from the shared factories in {@code com.iotee.platform.architecture}
 * ({@code iotee-architecture}, a test-scope-only artifact per ADR 0013
 * Decision 8) with this service's own package names -- this class is
 * where {@code services.identity} states which rules apply to it, not
 * where rule text lives.
 *
 * <p>Four rules, each with a "holds for real code" check (via
 * {@link ImportHelper#importProductionClasses}) and a negative test that
 * proves the rule actually fires (via
 * {@link ImportHelper#importFixturePackages}), the same two-scan
 * discipline this platform has used since {@code common}'s original
 * architecture tests:
 *
 * <ol>
 *   <li>{@code services.identity.core} must stay framework-free (no
 *       {@code org.springframework..}) -- carried forward unchanged from
 *       Track B's first attempt.</li>
 *   <li>{@code services.identity} must not depend on any sibling bounded
 *       context named in ADR 0012 Decision 6 -- carried forward
 *       unchanged. Currently vacuously true: none of
 *       billing/firmware/evidence/gateway/deployment_studio exist as
 *       Java packages yet (see
 *       {@link #allFiveSiblingIsolationRulesAreCurrentlyVacuous()}).</li>
 *   <li>{@code services.identity.rbac} must stay framework-free -- new
 *       in this refactor. RBAC is authorization business logic (ADR
 *       0013 Decision 1: it is exactly the kind of code that must not
 *       become a shared runtime library), and keeping it framework-free
 *       is what makes it safely copyable into a future service's own
 *       package without dragging a Spring dependency along with
 *       it.</li>
 *   <li>No class in this service's own build may reside in a retired
 *       shared package ({@code com.iotee.platform.common..},
 *       {@code com.iotee.platform.adapters.webspring..}) -- the ADR
 *       0013 Decision 5.2 autonomy tripwire, a source-level defense
 *       against reintroducing the exact shared-runtime anti-pattern
 *       ADR 0013 retired, catching it even without a matching
 *       {@code pom.xml} dependency declaration (the Maven Enforcer
 *       {@code bannedDependencies} rule in the parent {@code pom.xml}
 *       is the dependency-declaration-level defense; this rule is the
 *       source-level one).</li>
 * </ol>
 */
class IdentityArchitectureRulesTest {

    private static final String BASE_PACKAGE = "com.iotee.platform.identity";

    private static final String[] SIBLING_CONTEXTS = {
            "billing", "firmware", "evidence", "gateway", "deployment_studio"
    };

    private static final String[] RETIRED_SHARED_PACKAGES = {
            "com.iotee.platform.common..", "com.iotee.platform.adapters.webspring.."
    };

    private static JavaClasses importIdentityModule() {
        return ImportHelper.importProductionClasses(BASE_PACKAGE);
    }

    private static ArchRule identityCoreMustStayFrameworkFree() {
        return ArchRules.noClassesDependOnPackages(
                BASE_PACKAGE + ".core",
                "services.identity.core holds the walking skeleton's framework-free handler "
                        + "logic (TenantPermissionsHandler); Spring types belong in services.identity.web, "
                        + "the thin adapter that calls into core.",
                "org.springframework..");
    }

    private static ArchRule identityRbacMustStayFrameworkFree() {
        return ArchRules.noClassesDependOnPackages(
                BASE_PACKAGE + ".rbac",
                "services.identity.rbac is authorization business logic (ADR 0013 Decision 1); "
                        + "keeping it framework-free is what makes it safely copyable into a future "
                        + "service's own package (ADR 0013's accepted-duplication model) without also "
                        + "copying a Spring dependency it never needed.",
                "org.springframework..");
    }

    private static ArchRule identityMustNotDependOnSiblingContexts() {
        return ArchRules.noClassesDependOnSiblingBoundedContexts(
                BASE_PACKAGE,
                "services.identity is its own bounded context (ADR 0012 Decision 6); it may "
                        + "depend on its own packages and on ordinary third-party libraries, and on "
                        + "nothing under any sibling services/* context.",
                SIBLING_CONTEXTS);
    }

    private static ArchRule noClassesResideInRetiredSharedPackages() {
        return ArchRules.noClassesResideInRetiredSharedPackages(
                "ADR 0013 retired common/ and adapters/web-spring/ as shared runtime modules; "
                        + "this is the autonomy tripwire that catches either one being reintroduced at "
                        + "the source level, with or without a matching pom.xml dependency.",
                RETIRED_SHARED_PACKAGES);
    }

    @Test
    void identityCoreDoesNotDependOnSpring() {
        identityCoreMustStayFrameworkFree().check(importIdentityModule());
    }

    @Test
    void identityCoreFrameworkFreedomRuleActuallyCatchesAViolation() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.core.fixtures",
                "org.springframework.web.bind.annotation");

        EvaluationResult result = identityCoreMustStayFrameworkFree().evaluate(fixtureClasses);

        assertFalse(result.getFailureReport().isEmpty(),
                "the fixture class deliberately depends on a Spring-package stand-in; if this is "
                        + "empty, the core framework-freedom rule is not actually being enforced");
        assertTrue(
                result.getFailureReport().toString().contains("org.springframework"),
                () -> "expected the failure report to name org.springframework, got: "
                        + result.getFailureReport());
    }

    @Test
    void theIdentityCoreFrameworkFreedomFixtureViolationWouldFailIfRunAsARealAssertion() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.core.fixtures",
                "org.springframework.web.bind.annotation");

        assertThrows(AssertionError.class, () -> identityCoreMustStayFrameworkFree().check(fixtureClasses));
    }

    @Test
    void identityRbacDoesNotDependOnSpring() {
        identityRbacMustStayFrameworkFree().check(importIdentityModule());
    }

    @Test
    void identityRbacFrameworkFreedomRuleActuallyCatchesAViolation() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.rbac.fixtures",
                "org.springframework.stereotype");

        EvaluationResult result = identityRbacMustStayFrameworkFree().evaluate(fixtureClasses);

        assertFalse(result.getFailureReport().isEmpty(),
                "the fixture class deliberately depends on a Spring-package stand-in; if this is "
                        + "empty, the rbac framework-freedom rule is not actually being enforced");
        assertTrue(
                result.getFailureReport().toString().contains("org.springframework"),
                () -> "expected the failure report to name org.springframework, got: "
                        + result.getFailureReport());
    }

    @Test
    void theIdentityRbacFrameworkFreedomFixtureViolationWouldFailIfRunAsARealAssertion() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.rbac.fixtures",
                "org.springframework.stereotype");

        assertThrows(AssertionError.class, () -> identityRbacMustStayFrameworkFree().check(fixtureClasses));
    }

    @Test
    void identityDoesNotDependOnAnySiblingBoundedContext() {
        identityMustNotDependOnSiblingContexts().check(importIdentityModule());
    }

    @Test
    void allFiveSiblingIsolationRulesAreCurrentlyVacuous() {
        // None of billing/firmware/evidence/gateway/deployment_studio exist as
        // Java packages yet -- this test fails, forcing this class's Javadoc to
        // be revisited, the day any of them is added under services/.
        JavaClasses classes = importIdentityModule();
        for (String sibling : SIBLING_CONTEXTS) {
            boolean anyDependencyOnSibling = classes.stream()
                    .anyMatch(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                            .anyMatch(dependency ->
                                    dependency.getTargetClass().getPackageName().contains(".services." + sibling)));
            assertFalse(anyDependencyOnSibling,
                    "expected no dependency on services." + sibling + " yet -- if this now fails, "
                            + "the isolation rule for it is no longer vacuous and this test (and the "
                            + "class Javadoc) should be updated to say so");
        }
    }

    @Test
    void identityMustNotDependOnSiblingContextsRuleActuallyCatchesAViolation() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.architecture.fixtures");

        EvaluationResult result = identityMustNotDependOnSiblingContexts().evaluate(fixtureClasses);

        assertFalse(result.getFailureReport().isEmpty(),
                "the fixture package deliberately violates the rule; if this is empty, "
                        + "the rule is not actually being enforced");
        assertTrue(
                result.getFailureReport().toString().contains("billing"),
                () -> "expected the failure report to name the forbidden services.billing package, got: "
                        + result.getFailureReport());
    }

    @Test
    void theSiblingContextFixtureViolationWouldFailIfRunAsARealAssertion() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.identity.architecture.fixtures");

        assertThrows(AssertionError.class, () -> identityMustNotDependOnSiblingContexts().check(fixtureClasses));
    }

    @Test
    void noProductionClassResidesInARetiredSharedPackage() {
        noClassesResideInRetiredSharedPackages().check(importIdentityModule());
    }

    @Test
    void retiredSharedPackageTripwireRuleActuallyCatchesAViolation() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.common.rbac");

        EvaluationResult result = noClassesResideInRetiredSharedPackages().evaluate(fixtureClasses);

        assertFalse(result.getFailureReport().isEmpty(),
                "the fixture class deliberately resides in the retired com.iotee.platform.common.rbac "
                        + "package; if this is empty, the autonomy tripwire is not actually being "
                        + "enforced");
        assertTrue(
                result.getFailureReport().toString().contains("com.iotee.platform.common.rbac"),
                () -> "expected the failure report to name the retired com.iotee.platform.common.rbac "
                        + "package, got: " + result.getFailureReport());
    }

    @Test
    void theRetiredSharedPackageFixtureViolationWouldFailIfRunAsARealAssertion() {
        JavaClasses fixtureClasses = ImportHelper.importFixturePackages(
                "com.iotee.platform.common.rbac");

        assertThrows(AssertionError.class, () -> noClassesResideInRetiredSharedPackages().check(fixtureClasses));
    }
}
