package com.iotee.platform.identity.rbac.policy;

import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.DEVICE_1;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.DEVICE_2;
import static com.iotee.platform.identity.rbac.policy.PolicyFixtures.DEVICE_PROPERTY_WRITE;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GrantAuthorityTest {

    private static final String CHANNEL = IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE;

    private final PolicySet policy = PolicyFixtures.evaluatorPolicy();
    private final GrantAuthority authority = new GrantAuthority(policy, "tenant.manage_roles");
    private final PolicyDecisionPoint pdp = new PolicyDecisionPoint(policy);

    /** Tenant admin of TENANT_A holding manage_roles, telemetry and device writes for DEVICE_1, until NOW+1d. */
    private final Subject tenantAdminA = tenantAdmin("synthetic-ta-a", TENANT_A);
    private final List<Grant> tenantAdminAGrants = List.of(grant("g-ta", "synthetic-ta-a").tenant(TENANT_A)
            .permissions("tenant.manage_roles", TELEMETRY_VIEW, DEVICE_PROPERTY_WRITE)
            .devices(DEVICE_1).expiresAt(NOW.plus(Duration.ofDays(1))).build());

    private PolicyDecision decide(Subject grantor, List<Grant> grantorGrants, Grant proposed,
            SubjectTier granteeTier, String granteeTenant) {
        return authority.decide(new GrantRequest(grantor, grantorGrants, proposed, granteeTier, granteeTenant, NOW));
    }

    private static void assertDenied(DecisionReason expected, PolicyDecision decision) {
        assertFalse(decision.permitted(), () -> "expected a denial, got " + decision);
        assertEquals(expected, decision.reason());
    }

    // ------------------------------------------------ tenant-admin delegation

    @Test
    void aTenantAdminMayDelegateWithinItsOwnAuthority() {
        Grant proposed = grant("g-new", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE)
                .devices(DEVICE_1).expiresAt(NOW.plus(Duration.ofHours(8))).grantedBy("synthetic-ta-a").build();

        PolicyDecision decision = decide(tenantAdminA, tenantAdminAGrants, proposed, SubjectTier.TENANT_OPERATOR, TENANT_A);

        assertTrue(decision.permitted());
        assertEquals(DecisionReason.PERMIT_GRANT_VALID, decision.reason());
    }

    @Test
    void tenantRoleDelegationRequiresMfaAndFreshStepUpEvenWithAnActiveGrant() {
        Grant proposed = grant("g-new", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE)
                .devices(DEVICE_1).expiresAt(NOW.plus(Duration.ofHours(8))).grantedBy("synthetic-ta-a").build();
        Subject stale = new Subject("synthetic-ta-a", SubjectTier.TENANT_ADMIN, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.OTP, NOW.minus(Duration.ofMinutes(16)));
        Subject missingTime = new Subject("synthetic-ta-a", SubjectTier.TENANT_ADMIN, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.OTP, null);
        Subject singleFactor = new Subject("synthetic-ta-a", SubjectTier.TENANT_ADMIN, TENANT_A,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.SINGLE_FACTOR, NOW.minusSeconds(30));

        assertDenied(DecisionReason.DENY_STEP_UP_REQUIRED,
                decide(stale, tenantAdminAGrants, proposed, SubjectTier.TENANT_OPERATOR, TENANT_A));
        assertDenied(DecisionReason.DENY_STEP_UP_REQUIRED,
                decide(missingTime, tenantAdminAGrants, proposed, SubjectTier.TENANT_OPERATOR, TENANT_A));
        assertDenied(DecisionReason.DENY_AUTH_STRENGTH,
                decide(singleFactor, tenantAdminAGrants, proposed, SubjectTier.TENANT_OPERATOR, TENANT_A));
    }

    @Test
    void privilegeEscalationGrantingAPermissionTheGrantorLacksIsDenied() {
        Grant proposed = grant("g-new", "synthetic-op-1").tenant(TENANT_A).permissions(PolicyFixtures.FIRMWARE_DISPATCH)
                .expiresAt(NOW.plus(Duration.ofHours(1))).grantedBy("synthetic-ta-a").build();

        PolicyDecision decision = decide(tenantAdminA, tenantAdminAGrants, proposed, SubjectTier.TENANT_OPERATOR, TENANT_A);
        assertDenied(DecisionReason.DENY_EXCEEDS_GRANTOR, decision);
        assertEquals(PolicyFixtures.FIRMWARE_DISPATCH, decision.detail());
    }

    @Test
    void delegationCannotWidenDevicesOrOutliveTheGrantorsOwnGrant() {
        Grant allDevices = grant("g-new", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE)
                .expiresAt(NOW.plus(Duration.ofHours(1))).grantedBy("synthetic-ta-a").build();
        Grant otherDevice = grant("g-new", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE)
                .devices(DEVICE_2).expiresAt(NOW.plus(Duration.ofHours(1))).grantedBy("synthetic-ta-a").build();
        Grant outlives = grant("g-new", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE)
                .devices(DEVICE_1).expiresAt(NOW.plus(Duration.ofDays(2))).grantedBy("synthetic-ta-a").build();
        Grant noExpiry = grant("g-new", "synthetic-op-1").tenant(TENANT_A).permissions(DEVICE_PROPERTY_WRITE)
                .devices(DEVICE_1).grantedBy("synthetic-ta-a").build();

        for (Grant proposed : List.of(allDevices, otherDevice, outlives, noExpiry)) {
            assertDenied(DecisionReason.DENY_EXCEEDS_GRANTOR,
                    decide(tenantAdminA, tenantAdminAGrants, proposed, SubjectTier.TENANT_OPERATOR, TENANT_A));
        }
    }

    @Test
    void aTenantAdminWhoseOwnGrantWasRevokedCanNoLongerDelegate() {
        List<Grant> revoked = List.of(tenantAdminAGrants.get(0).revokedAt(NOW.minusSeconds(1)));
        Grant proposed = grant("g-new", "synthetic-op-1").tenant(TENANT_A).permissions(TELEMETRY_VIEW)
                .expiresAt(NOW.plus(Duration.ofHours(1))).grantedBy("synthetic-ta-a").build();

        assertDenied(DecisionReason.DENY_EXCEEDS_GRANTOR,
                decide(tenantAdminA, revoked, proposed, SubjectTier.TENANT_OPERATOR, TENANT_A));
    }

    @Test
    void crossTenantDelegationIsDenied() {
        Grant intoTenantB = grant("g-new", "synthetic-op-b").tenant(TENANT_B).permissions(TELEMETRY_VIEW)
                .grantedBy("synthetic-ta-a").build();
        assertDenied(DecisionReason.DENY_CROSS_TENANT,
                decide(tenantAdminA, tenantAdminAGrants, intoTenantB, SubjectTier.TENANT_OPERATOR, TENANT_B));

        Grant toTenantBOperator = grant("g-new", "synthetic-op-b").tenant(TENANT_A).permissions(TELEMETRY_VIEW)
                .grantedBy("synthetic-ta-a").build();
        assertDenied(DecisionReason.DENY_CROSS_TENANT,
                decide(tenantAdminA, tenantAdminAGrants, toTenantBOperator, SubjectTier.TENANT_OPERATOR, TENANT_B));
    }

    @Test
    void selfGrantsAreDeniedForEveryTier() {
        Grant self = grant("g-self", "synthetic-ta-a").tenant(TENANT_A).permissions(TELEMETRY_VIEW)
                .grantedBy("synthetic-ta-a").build();
        assertDenied(DecisionReason.DENY_SELF_GRANT,
                decide(tenantAdminA, tenantAdminAGrants, self, SubjectTier.TENANT_ADMIN, TENANT_A));

        Subject sysAdmin = productAdmin("synthetic-sysadmin-1");
        Grant selfChannel = grant("g-self", "synthetic-sysadmin-1").permissions(CHANNEL)
                .expiresAt(NOW.plus(Duration.ofHours(1))).approval("approval-1").grantedBy("synthetic-sysadmin-1").build();
        assertDenied(DecisionReason.DENY_SELF_GRANT,
                decide(sysAdmin, List.of(), selfChannel, SubjectTier.PRODUCT_ADMIN, null));
    }

    @Test
    void operatorsCannotGrantAnything() {
        Grant proposed = grant("g-new", "synthetic-op-2").tenant(TENANT_A).permissions(TELEMETRY_VIEW)
                .grantedBy("synthetic-op-1").build();
        assertDenied(DecisionReason.DENY_GRANTOR_TIER, decide(tenantOperator("synthetic-op-1", TENANT_A), List.of(),
                proposed, SubjectTier.TENANT_OPERATOR, TENANT_A));

        Grant channel = grant("g-new", "synthetic-op-2").permissions(CHANNEL).expiresAt(NOW.plus(Duration.ofHours(1)))
                .approval("approval-1").grantedBy("synthetic-sysop-1").build();
        assertDenied(DecisionReason.DENY_GRANTOR_TIER, decide(productOperator("synthetic-sysop-1"), List.of(),
                channel, SubjectTier.TENANT_OPERATOR, TENANT_A));
    }

    @Test
    void theGrantMustBeRecordedAsMadeByTheAuthenticatedGrantor() {
        Grant forged = grant("g-new", "synthetic-op-1").tenant(TENANT_A).permissions(TELEMETRY_VIEW)
                .grantedBy("synthetic-sysadmin-1").build();
        assertDenied(DecisionReason.DENY_GRANTOR_MISMATCH,
                decide(tenantAdminA, tenantAdminAGrants, forged, SubjectTier.TENANT_OPERATOR, TENANT_A));
    }

    // ------------------------------------------ environment-admin channel grants

    private Grant channelFor(String grantee, String grantor) {
        return grant("g-ch", grantee).permissions(CHANNEL).environments("prod")
                .expiresAt(NOW.plus(Duration.ofHours(2))).approval("approval-42").grantedBy(grantor).build();
    }

    @Test
    void aSystemAdminMayGrantAJustInTimeChannelWithApprovalAndExpiry() {
        Subject sysAdmin = productAdmin("synthetic-sysadmin-1");

        assertTrue(decide(sysAdmin, List.of(), channelFor("synthetic-op-1", "synthetic-sysadmin-1"),
                SubjectTier.TENANT_OPERATOR, TENANT_A).permitted());
        assertTrue(decide(sysAdmin, List.of(), channelFor("synthetic-sysop-1", "synthetic-sysadmin-1"),
                SubjectTier.PRODUCT_OPERATOR, null).permitted());
    }

    @Test
    void aTenantAdminCannotGrantAnEnvironmentChannelEvenToItsOwnOperators() {
        assertDenied(DecisionReason.DENY_PRIVILEGED_GRANTOR, decide(tenantAdminA, tenantAdminAGrants,
                channelFor("synthetic-op-1", "synthetic-ta-a"), SubjectTier.TENANT_OPERATOR, TENANT_A));
    }

    @Test
    void aChannelGrantNeedsApprovalAndABoundedExpiry() {
        Subject sysAdmin = productAdmin("synthetic-sysadmin-1");
        Grant noApproval = grant("g-ch", "synthetic-op-1").permissions(CHANNEL)
                .expiresAt(NOW.plus(Duration.ofHours(1))).grantedBy("synthetic-sysadmin-1").build();
        Grant noExpiry = grant("g-ch", "synthetic-op-1").permissions(CHANNEL)
                .approval("approval-1").grantedBy("synthetic-sysadmin-1").build();
        Grant tooLong = grant("g-ch", "synthetic-op-1").permissions(CHANNEL)
                .expiresAt(NOW.plus(IdentityPolicyV1.MAX_ENVIRONMENT_CHANNEL_GRANT).plusSeconds(1))
                .approval("approval-1").grantedBy("synthetic-sysadmin-1").build();

        assertDenied(DecisionReason.DENY_APPROVAL_REQUIRED,
                decide(sysAdmin, List.of(), noApproval, SubjectTier.TENANT_OPERATOR, TENANT_A));
        assertDenied(DecisionReason.DENY_EXPIRY_REQUIRED,
                decide(sysAdmin, List.of(), noExpiry, SubjectTier.TENANT_OPERATOR, TENANT_A));
        assertDenied(DecisionReason.DENY_EXPIRY_TOO_LONG,
                decide(sysAdmin, List.of(), tooLong, SubjectTier.TENANT_OPERATOR, TENANT_A));
    }

    @Test
    void aChannelGrantNeedsTheGrantorsMfaAndARecentStepUp() {
        Subject noMfa = new Subject("synthetic-sysadmin-1", SubjectTier.PRODUCT_ADMIN, null,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.SINGLE_FACTOR, NOW);
        Subject stale = new Subject("synthetic-sysadmin-1", SubjectTier.PRODUCT_ADMIN, null,
                ClientAudience.ADMIN_CONSOLE, AuthStrength.PHISHING_RESISTANT, NOW.minus(Duration.ofHours(1)));

        assertDenied(DecisionReason.DENY_AUTH_STRENGTH, decide(noMfa, List.of(),
                channelFor("synthetic-op-1", "synthetic-sysadmin-1"), SubjectTier.TENANT_OPERATOR, TENANT_A));
        assertDenied(DecisionReason.DENY_STEP_UP_REQUIRED, decide(stale, List.of(),
                channelFor("synthetic-op-1", "synthetic-sysadmin-1"), SubjectTier.TENANT_OPERATOR, TENANT_A));
    }

    @Test
    void anEnvironmentAdminNeverBecomesASystemAdmin() {
        Subject sysAdmin = productAdmin("synthetic-sysadmin-1");
        Grant channelPlusAdmin = grant("g-ch", "synthetic-sysop-1")
                .permissions(CHANNEL, IdentityPolicyV1.PLATFORM_ADMINISTER)
                .expiresAt(NOW.plus(Duration.ofHours(1))).approval("approval-1").grantedBy("synthetic-sysadmin-1").build();

        PolicyDecision decision = decide(sysAdmin, List.of(), channelPlusAdmin, SubjectTier.PRODUCT_OPERATOR, null);
        assertDenied(DecisionReason.DENY_TIER, decision);
        assertTrue(decision.detail().startsWith(IdentityPolicyV1.PLATFORM_ADMINISTER));
    }

    @Test
    void aChannelGrantCannotBeTenantScoped() {
        Subject sysAdmin = productAdmin("synthetic-sysadmin-1");
        Grant tenantScopedChannel = grant("g-ch", "synthetic-op-1").tenant(TENANT_A).permissions(CHANNEL)
                .expiresAt(NOW.plus(Duration.ofHours(1))).approval("approval-1").grantedBy("synthetic-sysadmin-1").build();

        assertDenied(DecisionReason.DENY_SCOPE_MISMATCH,
                decide(sysAdmin, List.of(), tenantScopedChannel, SubjectTier.TENANT_OPERATOR, TENANT_A));
    }

    @Test
    void revokingAChannelGrantEndsAccessImmediately() {
        Subject sysAdmin = productAdmin("synthetic-sysadmin-1");
        Grant channel = channelFor("synthetic-op-1", "synthetic-sysadmin-1");
        assertTrue(decide(sysAdmin, List.of(), channel, SubjectTier.TENANT_OPERATOR, TENANT_A).permitted());

        Subject operator = tenantOperator("synthetic-op-1", TENANT_A);
        AccessRequest use = new AccessRequest(operator, CHANNEL, ResourceRef.environment("prod"), Set.of(), List.of(channel), NOW);
        assertTrue(pdp.decide(use).permitted());

        Grant revoked = channel.revokedAt(NOW);
        PolicyDecision afterRevocation = pdp.decide(new AccessRequest(operator, CHANNEL, ResourceRef.environment("prod"),
                Set.of(), List.of(revoked), NOW));
        assertDenied(DecisionReason.DENY_GRANT_REVOKED, afterRevocation);
    }
}
