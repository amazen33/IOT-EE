package com.iotee.platform.identity.rbac;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * A single, granular permission this platform recognizes.
 *
 * <p>Mirrors {@code spec/domain_core/rbac.py}'s {@code PERMISSION_CATALOG}
 * shape: a fixed, versioned catalog of dotted-string permission keys
 * (there, a {@code frozenset[str]}; here, a Java {@code enum} so an
 * unknown permission fails to compile instead of failing silently at
 * runtime). {@link #key()} exposes the same dotted-string identifier
 * convention the Python catalog uses (e.g. {@code "tenant.view"}), so a
 * permission's wire/log representation matches across both stacks even
 * though the Java side is typed.
 *
 * <p>This is a bootstrap set only (ADR 0012 non-goals: no full RBAC/ABAC
 * yet) -- two entries, not the Python catalog's full set. Extend this
 * enum (never repurpose an existing entry's {@link #key()} meaning, same
 * rule the Python catalog's own comment states) as {@code
 * services/identity} needs real permissions.
 *
 * <p><b>Owned by {@code services/identity} alone</b> (ADR 0013 Decision
 * 1/6): this enum is this service's own permission catalog, not a
 * shared platform-wide one. A future service with its own permissions
 * defines its own enum in its own package -- duplicated deliberately,
 * per ADR 0013, rather than extending this one or depending on it.
 */
public enum Permission {
    TENANT_READ("tenant.view"),
    TENANT_MANAGE("tenant.manage_roles");

    private final String key;

    Permission(String key) {
        this.key = key;
    }

    /** The dotted-string catalog key, matching the Python catalog's naming convention. */
    public String key() {
        return key;
    }

    /** The fixed, versioned permission catalog, as dotted-string keys -- the Java analog of PERMISSION_CATALOG. */
    public static Set<String> catalog() {
        return java.util.Arrays.stream(values()).map(Permission::key).collect(Collectors.toUnmodifiableSet());
    }
}
