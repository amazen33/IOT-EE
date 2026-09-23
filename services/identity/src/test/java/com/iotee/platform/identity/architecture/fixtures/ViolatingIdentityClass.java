package com.iotee.platform.identity.architecture.fixtures;

import com.iotee.platform.identity.architecture.fixtures.services.billing.FakeBillingClass;

/**
 * Deliberately violates the "services.identity must not depend on a
 * sibling bounded context" rule asserted in
 * {@code IdentityArchitectureRulesTest}. Never imported by any real
 * {@code services.identity} class.
 */
public class ViolatingIdentityClass {
    public String delegate() {
        return new FakeBillingClass().describe();
    }
}
