package com.iotee.platform.identity.architecture.fixtures;

import com.iotee.platform.billing.FakeBillingClass;

/**
 * Deliberately violates the "services.identity must not depend on a
 * sibling bounded context" rule asserted in
 * {@code IdentityArchitectureRulesTest} by depending on a class under
 * the real {@code com.iotee.platform.billing} sibling-context package
 * name (see {@code FakeBillingClass}'s Javadoc for why a stub under the
 * real package name, rather than a nested fixture look-alike, is
 * required for this negative test to prove anything). Never imported by
 * any real {@code services.identity} class.
 */
public class ViolatingIdentityClass {
    public String delegate() {
        return new FakeBillingClass().describe();
    }
}
