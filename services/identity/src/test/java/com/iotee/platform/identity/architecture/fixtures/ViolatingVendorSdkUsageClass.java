package com.iotee.platform.identity.architecture.fixtures;

import org.apache.kafka.FakeKafkaProducerClass;

/**
 * Deliberately violates the "no vendor SDK outside adapters" rule
 * asserted in {@code IdentityFrameworkFreedomArchitectureRulesTest} by
 * depending directly on a class under {@code org.apache.kafka} (see
 * {@code FakeKafkaProducerClass}'s Javadoc for why a stub under the
 * real package name is used). Never imported by any real
 * {@code services.identity} class -- test-fixture only.
 */
public class ViolatingVendorSdkUsageClass {
    public void useKafkaDirectly() {
        new FakeKafkaProducerClass().send();
    }
}
