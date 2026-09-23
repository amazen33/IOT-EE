package com.iotee.platform.identity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TenantTest {

    @Test
    void exposesItsIdAndDisplayName() {
        TenantId id = TenantId.of("synthetic-tenant-acme-001");
        Tenant tenant = new Tenant(id, "Synthetic Acme Corp");

        assertEquals(id, tenant.id());
        assertEquals("Synthetic Acme Corp", tenant.displayName());
    }

    @Test
    void rejectsBlankDisplayName() {
        TenantId id = TenantId.of("synthetic-tenant-acme-001");
        assertThrows(IllegalArgumentException.class, () -> new Tenant(id, "  "));
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () -> new Tenant(null, "Synthetic Acme Corp"));
    }

    @Test
    void rejectsNullDisplayName() {
        TenantId id = TenantId.of("synthetic-tenant-acme-001");
        assertThrows(NullPointerException.class, () -> new Tenant(id, null));
    }
}
