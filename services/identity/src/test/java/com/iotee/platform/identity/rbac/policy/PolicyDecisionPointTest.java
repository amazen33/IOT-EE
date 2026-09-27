package com.iotee.platform.identity.rbac.policy;

import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.ALARM_ACKNOWLEDGE;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.DEVICE_1;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.DEVICE_2;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.DEVICE_PROPERTY_WRITE;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.FIRMWARE_DISPATCH;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.FIRMWARE_ENTITLEMENT;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.NOW;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.TELEMETRY_VIEW;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.TENANT_A;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.TENANT_B;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.grant;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.productAdmin;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.productOperator;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.tenantAdmin;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.tenantOperator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyDecisionPointTest {

    private final PolicySet policy = PolicyFixtures.evaluatorPolicy();
    private final PolicyDecisionPoint pdp = new PolicyDecisionPoint(policy);

    private PolicyDecision decide(Subject subject, String permission, ResourceRef resource,
            Set<String> entitlements, List<Grant> grants) {
        return pdp.decide(new AccessRequest(subject, permission, resource, entitlements, grants, NOW));
    }

    private static void assertDenied(DecisionReason expected, PolicyDecision decision) {
        assertFalse(decision.permitted(), () -> "expected a denial, got " + decision);
        assertEquals(expected, decision.reason());
    }

    // ------------------------------------------------------------ basics

    @Test
    void anActiveGrantPermitsAndTheDecisionNamesItAndThePolicyVersion() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant g = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE).build();

        PolicyDecision decision = decide(op, DEVICE_PROPERTY_WRITE, ResourceRef.device(TENANT_A, DEVICE_1), Set.of(), List.of(g));

        assertTrue(decision.permitted());
        assertEquals(DecisionReason.PERMIT_ACTIVE_GRANT, decision.reason());
        assertEquals("g-1", decision.detail());
        assertEquals(7, decision.policyVersion());
    }

    @Test
    void aPermissionWithoutPolicyDataIsDeniedByDefault() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant g = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions("not.in.policy").build();

        assertDenied(DecisionReason.DENY_UNKNOWN_PERMISSION,
                decide(op, "not.in.policy", ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)));
    }

    @Test
    void noGrantMeansDeny() {
        assertDenied(DecisionReason.DENY_NO_ACTIVE_GRANT, decide(tenantOperator("synthetic-op-1", TENANT_A),
                TELEMETRY_VIEW, ResourceRef.tenant(TENANT_A), Set.of(), List.of()));
    }

    @Test
    void grantsHeldByOtherSubjectsAreIgnored() {
        Grant someoneElses = grant("g-x", "synthetic-op-2").tenant(TENANT_A).permissions(TELEMETRY_VIEW).build();

        assertDenied(DecisionReason.DENY_NO_ACTIVE_GRANT, decide(tenantOperator("synthetic-op-1", TENANT_A),
                TELEMETRY_VIEW, ResourceRef.tenant(TENANT_A), Set.of(), List.of(someoneElses)));
    }

    // ------------------------------------------------------- cross-tenant

    @Test
    void aTenantSubjectIsDeniedInAnotherTenantEvenWithAGrantNamingThatTenant() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant misissued = grant("g-b", "synthetic-op-1").tenant(TENANT_B).permissions(TELEMETRY_VIEW).build();

        assertDenied(DecisionReason.DENY_CROSS_TENANT,
                decide(op, TELEMETRY_VIEW, ResourceRef.tenant(TENANT_B), Set.of(), List.of(misissued)));
    }

    @Test
    void aTenantGrantDoesNotApplyToAnotherTenantsDevices() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant g = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE).build();

        assertDenied(DecisionReason.DENY_CROSS_TENANT,
                decide(op, DEVICE_PROPERTY_WRITE, ResourceRef.device(TENANT_B, "synthetic-device-b-001"), Set.of(), List.of(g)));
    }

    @Test
    void aSystemAdminHasNoImplicitAccessToTenantResources() {
        Subject admin = productAdmin("synthetic-sysadmin-1");

        assertDenied(DecisionReason.DENY_NO_ACTIVE_GRANT,
                decide(admin, "tenant.view", ResourceRef.tenant(TENANT_A), Set.of(), List.of()));

        Grant explicit = grant("g-audited", "synthetic-sysadmin-1").tenant(TENANT_A).permissions("tenant.view").build();
        assertTrue(decide(admin, "tenant.view", ResourceRef.tenant(TENANT_A), Set.of(), List.of(explicit)).permitted());
        assertDenied(DecisionReason.DENY_NO_ACTIVE_GRANT,
                decide(admin, "tenant.view", ResourceRef.tenant(TENANT_B), Set.of(), List.of(explicit)));
    }

    // -------------------------------------------------------- explicit deny

    @Test
    void anExplicitDenyWinsOverAnActiveGrant() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant g = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE).build();
        PolicyDecisionPoint withDeny = new PolicyDecisionPoint(policy.withDenyRule(
                new DenyRule("deny-device-1", DEVICE_PROPERTY_WRITE, null, null, TENANT_A, DEVICE_1)));

        PolicyDecision denied = withDeny.decide(new AccessRequest(op, DEVICE_PROPERTY_WRITE,
                ResourceRef.device(TENANT_A, DEVICE_1), Set.of(), List.of(g), NOW));
        assertDenied(DecisionReason.DENY_EXPLICIT_RULE, denied);
        assertEquals("deny-device-1", denied.detail());
        assertEquals(8, denied.policyVersion());

        assertTrue(withDeny.decide(new AccessRequest(op, DEVICE_PROPERTY_WRITE,
                ResourceRef.device(TENANT_A, DEVICE_2), Set.of(), List.of(g), NOW)).permitted());
    }

    @Test
    void anExplicitDenyAlsoBindsSystemAdmins() {
        Subject admin = productAdmin("synthetic-sysadmin-1");
        Grant g = grant("g-1", "synthetic-sysadmin-1").permissions(IdentityPolicyV1.PLATFORM_ADMINISTER).build();
        PolicyDecisionPoint withDeny = new PolicyDecisionPoint(policy.withDenyRule(
                new DenyRule("freeze-admin", DenyRule.ANY_PERMISSION, "synthetic-sysadmin-1", null, null, null)));

        assertDenied(DecisionReason.DENY_EXPLICIT_RULE, withDeny.decide(new AccessRequest(admin,
                IdentityPolicyV1.PLATFORM_ADMINISTER, new ResourceRef(null, null, null), Set.of(), List.of(g), NOW)));
    }

    // ---------------------------------------------- audiences (token interchange)

    @Test
    void anOperatorWhoPresentsAnAdminConsoleSessionIsDenied() {
        Subject operatorWithAdminAudience = new Subject("synthetic-op-1", SubjectTier.TENANT_OPERATOR, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.OTP, NOW);
        Grant g = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(TELEMETRY_VIEW).build();

        assertDenied(DecisionReason.DENY_AUDIENCE_TIER_MISMATCH, decide(operatorWithAdminAudience, TELEMETRY_VIEW,
                ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)));
    }

    @Test
    void anAdminConsolePermissionIsNotExercisableFromTheOperatorConsole() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant g = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions("tenant.manage_roles").build();

        assertDenied(DecisionReason.DENY_AUDIENCE,
                decide(op, "tenant.manage_roles", ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)));
    }

    // ------------------------------------------------ revocation and expiry

    @Test
    void aRevokedGrantDeniesFromTheMomentOfRevocation() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant active = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(TELEMETRY_VIEW).build();
        assertTrue(decide(op, TELEMETRY_VIEW, ResourceRef.tenant(TENANT_A), Set.of(), List.of(active)).permitted());

        Grant revoked = active.revokedAt(NOW);

        PolicyDecision decision = decide(op, TELEMETRY_VIEW, ResourceRef.tenant(TENANT_A), Set.of(), List.of(revoked));
        assertDenied(DecisionReason.DENY_GRANT_REVOKED, decision);
        assertEquals("g-1", decision.detail());
    }

    @Test
    void revocationIsNotUndoneByRevokingAgain() {
        Grant revoked = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(TELEMETRY_VIEW).build()
                .revokedAt(NOW.minusSeconds(10));
        assertEquals(NOW.minusSeconds(10), revoked.revokedAt(NOW.plusSeconds(10)).revokedAt());
    }

    @Test
    void anExpiredGrantIsDenied() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant expired = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(TELEMETRY_VIEW)
                .expiresAt(NOW).build();

        assertDenied(DecisionReason.DENY_GRANT_EXPIRED,
                decide(op, TELEMETRY_VIEW, ResourceRef.tenant(TENANT_A), Set.of(), List.of(expired)));
    }

    // ---------------------------------------------------- device assignment

    @Test
    void aDeviceOutsideTheOperatorsAllowlistIsDenied() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant onlyDevice1 = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE)
                .devices(DEVICE_1).build();

        assertTrue(decide(op, DEVICE_PROPERTY_WRITE, ResourceRef.device(TENANT_A, DEVICE_1), Set.of(), List.of(onlyDevice1)).permitted());
        assertDenied(DecisionReason.DENY_NO_ACTIVE_GRANT,
                decide(op, DEVICE_PROPERTY_WRITE, ResourceRef.device(TENANT_A, DEVICE_2), Set.of(), List.of(onlyDevice1)));
    }

    @Test
    void aDeviceScopedPermissionRequiresANamedDevice() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant g = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE).build();

        assertDenied(DecisionReason.DENY_DEVICE_REQUIRED,
                decide(op, DEVICE_PROPERTY_WRITE, ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)));
    }

    // ------------------------------------------ entitlements (firmware bypass)

    @Test
    void firmwareIsDeniedWithoutTheTenantEntitlementEvenWithThePermission() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant g = grant("g-fw", "synthetic-op-1").tenant(TENANT_A).permissions(FIRMWARE_DISPATCH).build();

        PolicyDecision decision = decide(op, FIRMWARE_DISPATCH, ResourceRef.device(TENANT_A, DEVICE_1), Set.of(), List.of(g));
        assertDenied(DecisionReason.DENY_ENTITLEMENT, decision);
        assertEquals(FIRMWARE_ENTITLEMENT, decision.detail());

        assertTrue(decide(op, FIRMWARE_DISPATCH, ResourceRef.device(TENANT_A, DEVICE_1),
                Set.of(FIRMWARE_ENTITLEMENT), List.of(g)).permitted());
    }

    @Test
    void firmwareNeedsTheEntitlementEvenForASystemAdminWithATenantGrant() {
        Subject admin = productAdmin("synthetic-sysadmin-1");
        Grant g = grant("g-fw", "synthetic-sysadmin-1").tenant(TENANT_A).permissions(FIRMWARE_DISPATCH).build();

        assertDenied(DecisionReason.DENY_ENTITLEMENT,
                decide(admin, FIRMWARE_DISPATCH, ResourceRef.device(TENANT_A, DEVICE_1), Set.of(), List.of(g)));
    }

    @Test
    void firmwareNeedsTheEntitlementAndThePermissionAndTheDevice() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant writeOnly = grant("g-1", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE).build();
        Grant fwDevice1 = grant("g-2", "synthetic-op-1").tenant(TENANT_A).permissions(FIRMWARE_DISPATCH).devices(DEVICE_1).build();
        Set<String> entitled = Set.of(FIRMWARE_ENTITLEMENT);

        assertDenied(DecisionReason.DENY_NO_ACTIVE_GRANT,
                decide(op, FIRMWARE_DISPATCH, ResourceRef.device(TENANT_A, DEVICE_1), entitled, List.of(writeOnly)));
        assertDenied(DecisionReason.DENY_NO_ACTIVE_GRANT,
                decide(op, FIRMWARE_DISPATCH, ResourceRef.device(TENANT_A, DEVICE_2), entitled, List.of(fwDevice1)));
        assertTrue(decide(op, FIRMWARE_DISPATCH, ResourceRef.device(TENANT_A, DEVICE_1), entitled, List.of(fwDevice1)).permitted());
    }

    @Test
    void essentialCapabilitiesStayAvailableWithNoEntitlementsAtAll() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant g = grant("g-1", "synthetic-op-1").tenant(TENANT_A)
                .permissions(TELEMETRY_VIEW, ALARM_ACKNOWLEDGE, DEVICE_PROPERTY_WRITE).build();

        assertTrue(decide(op, TELEMETRY_VIEW, ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)).permitted());
        assertTrue(decide(op, ALARM_ACKNOWLEDGE, ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)).permitted());
        assertTrue(decide(op, DEVICE_PROPERTY_WRITE, ResourceRef.device(TENANT_A, DEVICE_1), Set.of(), List.of(g)).permitted());
    }

    @Test
    void anEssentialCapabilityCannotBeDeclaredWithAnEntitlementGate() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                PermissionPolicy.builder("fixture.alarm.view")
                        .audiences(ClientAudience.OPERATOR_CONSOLE)
                        .tiers(SubjectTier.TENANT_OPERATOR)
                        .essential()
                        .requiresEntitlement("premium-alarms")
                        .build());
        assertTrue(ex.getMessage().contains("essential"));
    }

    // ------------------------------------------------------- MFA and step-up

    @Test
    void anAdminWithoutMfaCannotManageRoles() {
        Subject singleFactor = new Subject("synthetic-ta-1", SubjectTier.TENANT_ADMIN, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.SINGLE_FACTOR, NOW.minusSeconds(30));
        Grant g = grant("g-1", "synthetic-ta-1").tenant(TENANT_A).permissions("tenant.manage_roles").build();

        assertDenied(DecisionReason.DENY_AUTH_STRENGTH,
                decide(singleFactor, "tenant.manage_roles", ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)));
    }

    @Test
    void aSensitiveChangeNeedsARecentStepUp() {
        Subject stale = new Subject("synthetic-ta-1", SubjectTier.TENANT_ADMIN, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.PHISHING_RESISTANT, NOW.minus(Duration.ofMinutes(16)));
        Grant g = grant("g-1", "synthetic-ta-1").tenant(TENANT_A).permissions("tenant.manage_roles").build();

        assertDenied(DecisionReason.DENY_STEP_UP_REQUIRED,
                decide(stale, "tenant.manage_roles", ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)));

        Subject fresh = new Subject("synthetic-ta-1", SubjectTier.TENANT_ADMIN, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.OTP, NOW.minus(Duration.ofMinutes(14)));
        assertTrue(decide(fresh, "tenant.manage_roles", ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)).permitted());

        Subject missingAuthTime = new Subject("synthetic-ta-1", SubjectTier.TENANT_ADMIN, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.OTP, null);
        assertDenied(DecisionReason.DENY_STEP_UP_REQUIRED,
                decide(missingAuthTime, "tenant.manage_roles", ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)));

        Subject futureAuthTime = new Subject("synthetic-ta-1", SubjectTier.TENANT_ADMIN, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.OTP, NOW.plusSeconds(1));
        assertDenied(DecisionReason.DENY_STEP_UP_REQUIRED,
                decide(futureAuthTime, "tenant.manage_roles", ResourceRef.tenant(TENANT_A), Set.of(), List.of(g)));
    }

    // ------------------------------------------- environment-admin channels

    @Test
    void anEnvironmentChannelGrantNeverConfersPlatformAdministration() {
        Subject sysOp = productOperator("synthetic-sysop-1");
        Grant channel = grant("g-ch", "synthetic-sysop-1")
                .permissions(IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE, IdentityPolicyV1.PLATFORM_ADMINISTER)
                .expiresAt(NOW.plus(Duration.ofHours(1))).approval("approval-1").build();

        assertDenied(DecisionReason.DENY_AUDIENCE, decide(sysOp, IdentityPolicyV1.PLATFORM_ADMINISTER,
                ResourceRef.environment("prod"), Set.of(), List.of(channel)));
        Subject sysOpOnAdminConsole = new Subject("synthetic-sysop-1", SubjectTier.PRODUCT_OPERATOR, null,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.OTP, NOW);
        assertDenied(DecisionReason.DENY_AUDIENCE_TIER_MISMATCH, decide(sysOpOnAdminConsole,
                IdentityPolicyV1.PLATFORM_ADMINISTER, ResourceRef.environment("prod"), Set.of(), List.of(channel)));

        Subject tenantAdminWithMisissuedGrant = tenantAdmin("synthetic-ta-1", TENANT_A);
        Grant misissued = grant("g-bad", "synthetic-ta-1").permissions(IdentityPolicyV1.PLATFORM_ADMINISTER).build();
        assertDenied(DecisionReason.DENY_TIER, decide(tenantAdminWithMisissuedGrant,
                IdentityPolicyV1.PLATFORM_ADMINISTER, new ResourceRef(null, null, null), Set.of(), List.of(misissued)));
    }

    @Test
    void anEnvironmentChannelGrantIsLimitedToItsEnvironmentsAndNeverTouchesTenantResources() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant devOnly = grant("g-ch", "synthetic-op-1").permissions(IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE)
                .environments("dev").expiresAt(NOW.plus(Duration.ofHours(1))).approval("approval-1").build();

        assertTrue(decide(op, IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE, ResourceRef.environment("dev"),
                Set.of(), List.of(devOnly)).permitted());
        assertDenied(DecisionReason.DENY_NO_ACTIVE_GRANT, decide(op, IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE,
                ResourceRef.environment("prod"), Set.of(), List.of(devOnly)));
        assertDenied(DecisionReason.DENY_SCOPE_MISMATCH, decide(op, IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE,
                new ResourceRef(TENANT_A, "dev", null), Set.of(), List.of(devOnly)));
        assertDenied(DecisionReason.DENY_NO_ACTIVE_GRANT,
                decide(op, TELEMETRY_VIEW, ResourceRef.tenant(TENANT_A), Set.of(), List.of(devOnly)));
    }

    @Test
    void anExpiredEnvironmentChannelGrantIsDenied() {
        Subject op = tenantOperator("synthetic-op-1", TENANT_A);
        Grant lapsed = grant("g-ch", "synthetic-op-1").permissions(IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE)
                .expiresAt(NOW.minusSeconds(1)).approval("approval-1").build();

        assertDenied(DecisionReason.DENY_GRANT_EXPIRED, decide(op, IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE,
                ResourceRef.environment("dev"), Set.of(), List.of(lapsed)));
    }

    // ------------------------------------------------------------ inputs

    @Test
    void subjectsMustCarryAConsistentTenant() {
        assertThrows(IllegalArgumentException.class, () -> new Subject("s", SubjectTier.TENANT_OPERATOR, null,
                ClientAudience.OPERATOR_CONSOLE, AuthStrength.OTP, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Subject("s", SubjectTier.PRODUCT_ADMIN, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.OTP, NOW));
    }

    @Test
    void aGrantMustCarryAPermissionAndAnOrderedLifetime() {
        assertThrows(IllegalArgumentException.class, () -> grant("g", "s").tenant(TENANT_A).build());
        Instant start = NOW;
        assertThrows(IllegalArgumentException.class, () -> new Grant("g", "s", TENANT_A, Set.of(TELEMETRY_VIEW),
                null, null, start, start, null, "grantor", null));
    }
}
