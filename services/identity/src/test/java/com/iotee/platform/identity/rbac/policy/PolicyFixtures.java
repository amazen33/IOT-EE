package com.iotee.platform.identity.rbac.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Synthetic subjects, grants and policy data for the policy tests.
 *
 * <p>{@link #evaluatorPolicy()} adds fixture permissions for device property
 * writes, firmware dispatch, telemetry and alarms on top of
 * {@link IdentityPolicyV1}. They exist only to exercise the evaluator's
 * semantics (device assignment, entitlement gating, essential capabilities);
 * the device-command and firmware services that will own such permissions
 * do not exist in Java yet.
 */
final class PolicyFixtures {

    static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    static final String TENANT_A = "synthetic-tenant-a";
    static final String TENANT_B = "synthetic-tenant-b";
    static final String DEVICE_1 = "synthetic-device-a-001";
    static final String DEVICE_2 = "synthetic-device-a-002";

    static final String DEVICE_PROPERTY_WRITE = "fixture.device.property.write";
    static final String FIRMWARE_DISPATCH = "fixture.firmware.dispatch";
    static final String TELEMETRY_VIEW = "fixture.telemetry.view";
    static final String ALARM_ACKNOWLEDGE = "fixture.alarm.acknowledge";
    static final String FIRMWARE_ENTITLEMENT = "firmware-management";

    private PolicyFixtures() {
    }

    static PolicySet evaluatorPolicy() {
        List<PermissionPolicy> policies = new ArrayList<>();
        for (String key : List.of("tenant.view", "tenant.manage_roles", IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE,
                IdentityPolicyV1.ENVIRONMENT_CHANNEL_GRANT, IdentityPolicyV1.PLATFORM_ADMINISTER)) {
            policies.add(IdentityPolicyV1.policySet().policyFor(key).orElseThrow());
        }
        policies.add(PermissionPolicy.builder(DEVICE_PROPERTY_WRITE)
                .audiences(ClientAudience.OPERATOR_CONSOLE, ClientAudience.ADMIN_CONSOLE)
                .tiers(SubjectTier.TENANT_OPERATOR, SubjectTier.TENANT_ADMIN)
                .essential()
                .deviceScoped()
                .build());
        policies.add(PermissionPolicy.builder(FIRMWARE_DISPATCH)
                .audiences(ClientAudience.OPERATOR_CONSOLE, ClientAudience.ADMIN_CONSOLE)
                .tiers(SubjectTier.TENANT_OPERATOR, SubjectTier.TENANT_ADMIN, SubjectTier.PRODUCT_ADMIN)
                .minAuthStrength(AuthStrength.OTP)
                .stepUpWithin(Duration.ofMinutes(15))
                .requiresEntitlement(FIRMWARE_ENTITLEMENT)
                .deviceScoped()
                .build());
        policies.add(PermissionPolicy.builder(TELEMETRY_VIEW)
                .audiences(ClientAudience.OPERATOR_CONSOLE, ClientAudience.ADMIN_CONSOLE)
                .tiers(SubjectTier.TENANT_OPERATOR, SubjectTier.TENANT_ADMIN)
                .essential()
                .build());
        policies.add(PermissionPolicy.builder(ALARM_ACKNOWLEDGE)
                .audiences(ClientAudience.OPERATOR_CONSOLE, ClientAudience.ADMIN_CONSOLE)
                .tiers(SubjectTier.TENANT_OPERATOR, SubjectTier.TENANT_ADMIN)
                .essential()
                .build());
        return new PolicySet(7, policies, List.of());
    }

    static Subject tenantOperator(String id, String tenant) {
        return new Subject(id, SubjectTier.TENANT_OPERATOR, tenant, ClientAudience.OPERATOR_CONSOLE,
                AuthStrength.OTP, NOW.minusSeconds(60));
    }

    static Subject tenantAdmin(String id, String tenant) {
        return new Subject(id, SubjectTier.TENANT_ADMIN, tenant, ClientAudience.ADMIN_CONSOLE,
                AuthStrength.OTP, NOW.minusSeconds(60));
    }

    static Subject productAdmin(String id) {
        return new Subject(id, SubjectTier.PRODUCT_ADMIN, null, ClientAudience.ADMIN_CONSOLE,
                AuthStrength.PHISHING_RESISTANT, NOW.minusSeconds(60));
    }

    static Subject productOperator(String id) {
        return new Subject(id, SubjectTier.PRODUCT_OPERATOR, null, ClientAudience.OPERATOR_CONSOLE,
                AuthStrength.OTP, NOW.minusSeconds(60));
    }

    static GrantBuilder grant(String grantId, String subjectId) {
        return new GrantBuilder(grantId, subjectId);
    }

    /** Test-only builder so each test states just the attributes it is about. */
    static final class GrantBuilder {
        private final String grantId;
        private final String subjectId;
        private String tenantId;
        private Set<String> permissions = Set.of();
        private Set<String> deviceIds;
        private Set<String> environments;
        private Instant expiresAt;
        private Instant revokedAt;
        private String grantedBy = "synthetic-grantor";
        private String approvalRef;

        private GrantBuilder(String grantId, String subjectId) {
            this.grantId = grantId;
            this.subjectId = subjectId;
        }

        GrantBuilder tenant(String tenant) {
            this.tenantId = tenant;
            return this;
        }

        GrantBuilder permissions(String... keys) {
            this.permissions = Set.of(keys);
            return this;
        }

        GrantBuilder devices(String... ids) {
            this.deviceIds = Set.of(ids);
            return this;
        }

        GrantBuilder environments(String... names) {
            this.environments = Set.of(names);
            return this;
        }

        GrantBuilder expiresAt(Instant at) {
            this.expiresAt = at;
            return this;
        }

        GrantBuilder revokedAt(Instant at) {
            this.revokedAt = at;
            return this;
        }

        GrantBuilder grantedBy(String subject) {
            this.grantedBy = subject;
            return this;
        }

        GrantBuilder approval(String ref) {
            this.approvalRef = ref;
            return this;
        }

        Grant build() {
            return new Grant(grantId, subjectId, tenantId, permissions, deviceIds, environments,
                    null, expiresAt, revokedAt, grantedBy, approvalRef);
        }
    }
}
