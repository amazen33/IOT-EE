package com.iotee.platform.identity.rbac.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iotee.platform.identity.rbac.Permission;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pins the reviewed invariants of this service's version-1 policy data. */
class IdentityPolicyV1Test {

    private final PolicySet v1 = IdentityPolicyV1.policySet();

    private PermissionPolicy policy(String key) {
        return v1.policyFor(key).orElseThrow();
    }

    @Test
    void everyRbacPermissionOfThisServiceHasPolicyData() {
        for (Permission permission : Permission.values()) {
            assertTrue(v1.policyFor(permission.key()).isPresent(), permission.key());
        }
    }

    @Test
    void managingRolesIsAnAdminConsoleActionNeedingMfaAndStepUp() {
        PermissionPolicy manage = policy(Permission.TENANT_MANAGE.key());
        assertEquals(Set.of(ClientAudience.ADMIN_CONSOLE), manage.audiences());
        assertTrue(manage.minAuthStrength().atLeast(AuthStrength.OTP));
        assertEquals(IdentityPolicyV1.SENSITIVE_STEP_UP, manage.maxAuthAge());
    }

    @Test
    void environmentChannelsArePrivilegedPlatformGrantsWithABoundedLifetime() {
        PermissionPolicy use = policy(IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE);
        assertTrue(use.platformScoped());
        assertTrue(use.privilegedGrant());
        assertEquals(IdentityPolicyV1.MAX_ENVIRONMENT_CHANNEL_GRANT, use.maxGrantDuration());
        assertFalse(use.tiers().contains(SubjectTier.TENANT_ADMIN), "tenant admins neither hold nor grant channels");

        PermissionPolicy grant = policy(IdentityPolicyV1.ENVIRONMENT_CHANNEL_GRANT);
        assertEquals(Set.of(SubjectTier.PRODUCT_ADMIN), grant.tiers());
    }

    @Test
    void platformAdministrationBelongsToSystemAdminsOnly() {
        assertEquals(Set.of(SubjectTier.PRODUCT_ADMIN), policy(IdentityPolicyV1.PLATFORM_ADMINISTER).tiers());
    }

    @Test
    void noIdentityPermissionIsGatedByAnEntitlement() {
        for (String key : Set.of(Permission.TENANT_READ.key(), Permission.TENANT_MANAGE.key(),
                IdentityPolicyV1.ENVIRONMENT_CHANNEL_USE, IdentityPolicyV1.ENVIRONMENT_CHANNEL_GRANT,
                IdentityPolicyV1.PLATFORM_ADMINISTER)) {
            assertNull(policy(key).requiredEntitlement(), key);
        }
    }
}
