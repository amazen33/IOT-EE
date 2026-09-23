package org.apache.kafka;

/**
 * Stands in for a real {@code org.apache.kafka.*} class -- test-fixture
 * only, declared under the REAL {@code org.apache.kafka} package name
 * (not a look-alike), because ArchUnit's
 * {@code resideInAnyPackage("org.apache.kafka..")} matches on the
 * fully-qualified package name string, not on which jar a class came
 * from. Placed at the matching directory relative to this module's
 * {@code src/test/java}, the same convention this module already uses
 * for its {@code org.springframework.*} stand-ins. Never referenced
 * from real {@code services.identity} source.
 */
public class FakeKafkaProducerClass {
    public void send() {
        // no-op stub
    }
}
