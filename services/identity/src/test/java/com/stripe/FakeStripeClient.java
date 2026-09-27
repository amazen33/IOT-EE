package com.stripe;

/**
 * Stands in for a real {@code com.stripe.*} class -- test-fixture only,
 * declared under the REAL package name because ArchUnit matches on the
 * package name string (same convention as {@code FakeKafkaProducerClass}).
 * Never referenced from real {@code services.identity} source: ADR 0017
 * Decision 4 and ADR 0018 Decision 6 confine a payment provider SDK to its
 * own adapter in the payments service.
 */
public class FakeStripeClient {
    public void createCheckoutSession() {
        // no-op stub
    }
}
