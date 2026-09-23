package com.iotee.platform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Shared scanning conventions for a service's own architecture test (ADR
 * 0013 Decision 8: this module is a build-time-only artifact, consumed
 * at test scope only).
 *
 * <p>Every services/* module's architecture test needs exactly two kinds
 * of scan, and getting the distinction wrong silently breaks the rule
 * that uses it (this repository has already hit this bug once, on
 * Track B's first attempt): a PRODUCTION scan must exclude test classes,
 * or a rule's own negative-test fixtures -- which necessarily live
 * inside the same package tree the rule scans -- get caught by the
 * production check itself, and the "rule holds for real code" test
 * fails on its own planted violations instead of passing on real code.
 * A FIXTURE scan is the opposite: it must see exactly the fixture
 * package (and nothing else production code depends on), and nothing
 * about it should exclude test classes, since the fixtures ARE test
 * classes.
 */
public final class ImportHelper {

    private ImportHelper() {
    }

    /**
     * Imports only the PRODUCTION (main-source) classes under {@code
     * basePackage}, excluding {@code target/test-classes} entirely.
     * Use this for every "the rule holds for this service's real code"
     * check -- never for a negative test, which must see its own
     * fixture package instead (see {@link #importFixturePackages}).
     */
    public static JavaClasses importProductionClasses(String basePackage) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(basePackage);
    }

    /**
     * Imports exactly the given fixture (and any fake-stub) packages,
     * with no {@code DO_NOT_INCLUDE_TESTS} restriction -- fixtures are
     * test classes, and a negative test's whole point is to see them.
     * Pass every package the fixture's own deliberate violation touches
     * (its own package plus any fake-stub package it depends on), the
     * same way each service's existing negative tests already do.
     */
    public static JavaClasses importFixturePackages(String... fixturePackages) {
        return new ClassFileImporter().importPackages(fixturePackages);
    }
}
