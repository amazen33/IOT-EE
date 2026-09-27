package com.iotee.platform.identity.rbac.policy;

/**
 * The resource an action targets. Every attribute is optional; a permission's
 * policy decides which ones must be present.
 *
 * @param tenantId    owning tenant; null for a platform-level resource
 * @param environment deployment environment (for example {@code dev}, {@code prod}); null if not applicable
 * @param deviceId    target device; required for device-scoped permissions
 */
public record ResourceRef(String tenantId, String environment, String deviceId) {

    public static ResourceRef tenant(String tenantId) {
        return new ResourceRef(tenantId, null, null);
    }

    public static ResourceRef device(String tenantId, String deviceId) {
        return new ResourceRef(tenantId, null, deviceId);
    }

    public static ResourceRef environment(String environment) {
        return new ResourceRef(null, environment, null);
    }
}
