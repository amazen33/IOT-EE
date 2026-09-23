package com.iotee.platform.identity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TenantIdTest {

    @Test
    void acceptsAWellFormedSyntheticTenantId() {
        TenantId id = TenantId.of("synthetic-tenant-acme-001");
        assertEquals("synthetic-tenant-acme-001", id.value());
    }

    @Test
    void rejectsAnIdMissingTheSyntheticPrefix() {
        assertThrows(IllegalArgumentException.class, () -> TenantId.of("acme-001"));
    }

    @Test
    void rejectsAnIdThatLooksLikeARealTenantId() {
        // The exact kind of value this validation exists to keep out of a
        // bootstrap-scope module (development contract: synthetic data only).
        assertThrows(IllegalArgumentException.class, () -> TenantId.of("tenant-prod-4471"));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TenantId.of(null));
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> TenantId.of(""));
    }

    @Test
    void rejectsUppercaseCharactersAfterThePrefix() {
        assertThrows(IllegalArgumentException.class, () -> TenantId.of("synthetic-tenant-ACME"));
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        TenantId a = TenantId.of("synthetic-tenant-x");
        TenantId b = TenantId.of("synthetic-tenant-x");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
