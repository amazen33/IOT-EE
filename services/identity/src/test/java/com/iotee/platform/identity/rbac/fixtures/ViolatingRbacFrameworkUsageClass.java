package com.iotee.platform.identity.rbac.fixtures;

import org.springframework.stereotype.FakeComponent;

/**
 * Deliberately violates the "identity.rbac must not depend on Spring"
 * rule asserted in {@code IdentityArchitectureRulesTest}: depends on a
 * class declared under the real {@code org.springframework.stereotype}
 * package name (see {@link FakeComponent}). Never imported by any real
 * {@code services.identity.rbac} class.
 */
public class ViolatingRbacFrameworkUsageClass {
    public FakeComponent delegate() {
        return new FakeComponent();
    }
}
