package com.iotee.platform.identity.domain.fixtures;

import org.springframework.web.bind.annotation.FakeRestController;

/**
 * Deliberately violates the "services.identity.domain must stay
 * framework-free" rule asserted in {@code IdentityArchitectureRulesTest}:
 * this class resides under {@code com.iotee.platform.identity.domain}
 * (matching the real {@code domain} package the rule protects) and is
 * annotated with a Spring-package stand-in (see
 * {@code FakeRestController}'s Javadoc for why a stub under the real
 * package name is used). Never referenced from real {@code domain}
 * source -- test-fixture only.
 */
@FakeRestController
public class ViolatingDomainFrameworkUsageClass {
}
