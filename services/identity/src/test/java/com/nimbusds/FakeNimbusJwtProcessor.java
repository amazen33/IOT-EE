package com.nimbusds;

/**
 * Stands in for a real {@code com.nimbusds.*} class -- test-fixture only,
 * declared under the REAL package name because ArchUnit matches on the
 * package name string (same convention as {@code FakeKafkaProducerClass}).
 * Never referenced from real {@code services.identity} source: ADR 0016
 * Decision 5 confines the JWT-signature-verification library to
 * {@code adapter.out.jwt.NimbusTokenSignatureVerifier} alone.
 */
public class FakeNimbusJwtProcessor {
    public void process() {
        // no-op stub
    }
}
