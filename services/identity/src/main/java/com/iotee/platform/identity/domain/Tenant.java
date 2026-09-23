package com.iotee.platform.identity.domain;

import java.util.Objects;

/**
 * The walking skeleton's one domain object (ADR 0012 Decision 6). Holds
 * only what {@link com.iotee.platform.identity.web.TenantPermissionsController}
 * needs -- a validated identity and a display name. Real tenant state
 * (status, plan, provisioning metadata, ...) is out of scope for this
 * first commit.
 */
public final class Tenant {

    private final TenantId id;
    private final String displayName;

    public Tenant(TenantId id, String displayName) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    public TenantId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }
}
