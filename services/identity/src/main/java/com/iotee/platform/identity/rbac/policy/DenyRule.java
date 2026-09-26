package com.iotee.platform.identity.rbac.policy;

import java.util.Objects;

/**
 * An explicit deny. It wins over every grant, for every tier, including
 * system admins. Every non-null field must match for the rule to apply;
 * {@code permission} may be {@value #ANY_PERMISSION}.
 */
public record DenyRule(
        String ruleId,
        String permission,
        String subjectId,
        SubjectTier tier,
        String tenantId,
        String deviceId) {

    public static final String ANY_PERMISSION = "*";

    public DenyRule {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(permission, "permission");
    }

    boolean matches(Subject subject, String requestedPermission, ResourceRef resource) {
        return (ANY_PERMISSION.equals(permission) || permission.equals(requestedPermission))
                && (subjectId == null || subjectId.equals(subject.subjectId()))
                && (tier == null || tier == subject.tier())
                && (tenantId == null || tenantId.equals(resource.tenantId()))
                && (deviceId == null || deviceId.equals(resource.deviceId()));
    }
}
