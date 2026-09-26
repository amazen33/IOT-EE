package com.iotee.platform.identity.architecture.fixtures;

import com.nimbusds.FakeNimbusJwtProcessor;

/**
 * Deliberately violates the "no vendor SDK outside adapters" rule for the
 * JWT/JOSE-library ({@code com.nimbusds}) entry of the denylist.
 * Test-fixture only.
 */
public class ViolatingJwtLibraryUsageClass {
    public void useJwtLibraryDirectly() {
        new FakeNimbusJwtProcessor().process();
    }
}
