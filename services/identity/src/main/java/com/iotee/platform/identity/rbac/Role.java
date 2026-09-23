package com.iotee.platform.identity.rbac;

import java.util.Set;

/**
 * A named bundle of {@link Permission}s. Bootstrap set only -- see
 * {@link Permission}'s Javadoc.
 */
public enum Role {
    TENANT_VIEWER(Set.of(Permission.TENANT_READ)),
    TENANT_ADMIN(Set.of(Permission.TENANT_READ, Permission.TENANT_MANAGE));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean grants(Permission permission) {
        return permissions.contains(permission);
    }
}
