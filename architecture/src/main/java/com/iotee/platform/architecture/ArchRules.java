package com.iotee.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Reusable ArchUnit rule FACTORIES (ADR 0013 Decision 8: build-time-only,
 * test-scope-only artifact -- see this module's {@code pom.xml}
 * description). Every method here returns a fresh {@link ArchRule} built
 * from the caller's own package roots; it holds no state and checks
 * nothing on its own. A services/* module's own architecture test calls
 * these factories with its own package names, then checks the returned
 * rule against {@link ImportHelper#importProductionClasses} for the
 * "holds for real code" assertion and against
 * {@link ImportHelper#importFixturePackages} for the negative test that
 * proves the rule actually fires (the same discipline every existing
 * architecture test in this platform already follows -- this module
 * only centralizes the rule TEXT, not the two-scan pattern each
 * caller's own test methods still author explicitly).
 *
 * <p>None of these factories reference any specific service's package
 * names -- that would make this module aware of individual services,
 * which is exactly the coupling ADR 0013 rules out. Every parameter a
 * caller passes is that caller's own.
 */
public final class ArchRules {

    private ArchRules() {
    }

    /**
     * No class residing in {@code packageRoot} (and its sub-packages)
     * may depend on any of {@code deniedPackages}. The general-purpose
     * framework-freedom / vendor-denylist shape used throughout this
     * platform's architecture tests (e.g. "this service's core/ package
     * must not depend on org.springframework..").
     */
    public static ArchRule noClassesDependOnPackages(
            String packageRoot, String because, String... deniedPackages) {
        return noClasses()
                .that().resideInAPackage(packageRoot + "..")
                .should().dependOnClassesThat().resideInAnyPackage(deniedPackages)
                .because(because);
    }

    /**
     * No class residing in {@code packageRoot} (and its sub-packages),
     * OUTSIDE of {@code exemptSubpackage}, may depend on any of {@code
     * deniedPackages}. Use this for a vendor-SDK denylist that an
     * adapter-shaped subpackage is allowed to touch while the rest of
     * the service may not (mirrors this platform's existing
     * no-vendor-SDK-outside-adapters shape).
     */
    public static ArchRule noClassesOutsideSubpackageDependOnPackages(
            String packageRoot, String exemptSubpackage, String because, String... deniedPackages) {
        return noClasses()
                .that().resideInAPackage(packageRoot + "..")
                .and().resideOutsideOfPackage(exemptSubpackage)
                .should().dependOnClassesThat().resideInAnyPackage(deniedPackages)
                .because(because);
    }

    /**
     * No class residing in {@code packageRoot} may depend on any
     * package matching {@code "..services.." + sibling + ".."} for any
     * {@code sibling} in {@code siblingContexts}. The sibling-
     * bounded-context isolation shape: a service may depend on its own
     * code and on ordinary third-party libraries, never on another
     * services/* module's internals.
     */
    public static ArchRule noClassesDependOnSiblingBoundedContexts(
            String packageRoot, String because, String... siblingContexts) {
        String[] forbiddenPackages = new String[siblingContexts.length];
        for (int i = 0; i < siblingContexts.length; i++) {
            forbiddenPackages[i] = "..services.." + siblingContexts[i] + "..";
        }
        return noClasses()
                .that().resideInAPackage(packageRoot + "..")
                .should().dependOnClassesThat().resideInAnyPackage(forbiddenPackages)
                .because(because);
    }

    /**
     * No class anywhere in this service's own build may reside in any
     * of {@code retiredSharedPackages} -- the ADR 0013 "autonomy
     * tripwire" (Decision 5.2): a mechanical, source-level defense
     * against reintroducing the exact anti-pattern this platform's own
     * history already produced once (Track B's first attempt built
     * {@code common.rbac}/{@code common.correlation} and
     * {@code adapters.webspring} as shared runtime modules; ADR 0013
     * retired both). This is a narrower guarantee than a full
     * cross-reactor "no two services share a package" scan (which needs
     * two or more real services to compare against and is future work,
     * flagged in this platform's ADRs as such) -- it catches the one
     * concrete regression this repository already knows how to name:
     * someone pasting RBAC or correlation code back in under the
     * retired package names, with or without a matching pom.xml
     * dependency (the Maven Enforcer bannedDependencies rule in the
     * parent pom catches the dependency-declaration case; this rule
     * catches the source-level case even without one).
     */
    public static ArchRule noClassesResideInRetiredSharedPackages(
            String because, String... retiredSharedPackages) {
        return noClasses()
                .should().resideInAnyPackage(retiredSharedPackages)
                .because(because);
    }
}
