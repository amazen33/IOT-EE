package com.iotee.platform.identity.application.fixtures;

import org.springframework.web.bind.annotation.FakeRestController;

/**
 * Deliberately violates the "services.identity.application must stay
 * framework-free" rule asserted in {@code IdentityArchitectureRulesTest}:
 * this class resides under {@code com.iotee.platform.identity.application}
 * (matching the real {@code application} package the rule protects) and
 * is annotated with a Spring-package stand-in. Never referenced from real
 * {@code application} source -- test-fixture only. (Formerly
 * {@code core.fixtures.ViolatingCoreFrameworkUsageClass}; step C1 renamed
 * {@code core} to {@code application}.)
 */
@FakeRestController
public class ViolatingApplicationFrameworkUsageClass {
}
