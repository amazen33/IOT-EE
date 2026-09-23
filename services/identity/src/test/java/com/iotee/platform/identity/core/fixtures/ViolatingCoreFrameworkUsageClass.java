package com.iotee.platform.identity.core.fixtures;

import org.springframework.web.bind.annotation.FakeRestController;

/**
 * Deliberately violates the "services.identity.core must stay
 * framework-free" rule asserted in
 * {@code IdentityArchitectureRulesTest}: this class resides under
 * {@code com.iotee.platform.identity.core} (matching the real
 * {@code core} package the rule protects) and is annotated with a
 * Spring-package stand-in. Never referenced from real {@code core}
 * source -- test-fixture only.
 */
@FakeRestController
public class ViolatingCoreFrameworkUsageClass {
}
