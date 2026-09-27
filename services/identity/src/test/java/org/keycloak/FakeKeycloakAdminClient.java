package org.keycloak;

/**
 * Stands in for a real {@code org.keycloak.*} class -- test-fixture only,
 * declared under the REAL package name because ArchUnit matches on the
 * package name string (same convention as {@code FakeKafkaProducerClass}).
 * Never referenced from real {@code services.identity} source: ADR 0016
 * Decision 5 confines the identity-provider SDK to the adapter that
 * implements the claims-mapping port.
 */
public class FakeKeycloakAdminClient {
    public void listRealms() {
        // no-op stub
    }
}
