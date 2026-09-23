package com.iotee.platform.billing;

/**
 * Stands in for a real {@code services.billing} class -- test-fixture
 * only, declared under the ACTUAL sibling-context package name this
 * platform uses ({@code com.iotee.platform.billing}, not a
 * {@code services.billing}-shaped guess), and placed at the matching
 * directory relative to this module's {@code src/test/java} (not
 * nested under any {@code fixtures} package), the same convention this
 * module already uses for its Spring stand-ins under
 * {@code org.springframework.*}. This is what lets ArchUnit's
 * {@code resideInAnyPackage} match fire for a class that actually
 * resides where a real {@code billing} bounded context would. Never
 * referenced from real {@code services.identity} source.
 */
public class FakeBillingClass {
    public String describe() {
        return "fake com.iotee.platform.billing class, test-fixture only";
    }
}
