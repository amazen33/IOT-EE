package com.iotee.platform.identity.rbac.policy;

import com.iotee.platform.identity.rbac.Permission;
import java.time.Duration;
import java.util.List;

/**
 * Version 1 of {@code services/identity}'s own policy data: the permissions
 * this service decides on. Other services (device commands, firmware,
 * billing) own their permissions and policy data in their own packages
 * (ADR 0013); none of them exists in Java yet.
 *
 * <p>Values are proposals pending the identity/authorization ADR
 * ({@code docs/adr/XXXX-proposed-identity-authorization-and-privileged-access.md}).
 */
public final class IdentityPolicyV1 {

    /** Use an environment-admin (bastion/VM) channel. Platform-scoped, privileged grant. */
    public static final String ENVIRONMENT_CHANNEL_USE = "platform.environment_channel.use";
    /** Grant or revoke environment-admin channel access. System admins only. */
    public static final String ENVIRONMENT_CHANNEL_GRANT = "platform.environment_channel.grant";
    /** Platform administration. System admins only; no grant can give it to another tier. */
    public static final String PLATFORM_ADMINISTER = "platform.administer";

    /** Longest a just-in-time environment-admin channel grant may last. */
    public static final Duration MAX_ENVIRONMENT_CHANNEL_GRANT = Duration.ofHours(4);
    /** Step-up window for sensitive changes. */
    public static final Duration SENSITIVE_STEP_UP = Duration.ofMinutes(15);

    private IdentityPolicyV1() {
    }

    public static PolicySet policySet() {
        return new PolicySet(1, List.of(
                PermissionPolicy.builder(Permission.TENANT_READ.key())
                        .audiences(ClientAudience.ADMIN_CONSOLE, ClientAudience.OPERATOR_CONSOLE)
                        .tiers(SubjectTier.PRODUCT_ADMIN, SubjectTier.PRODUCT_OPERATOR,
                                SubjectTier.TENANT_ADMIN, SubjectTier.TENANT_OPERATOR)
                        .build(),
                PermissionPolicy.builder(Permission.TENANT_MANAGE.key())
                        .audiences(ClientAudience.ADMIN_CONSOLE)
                        .tiers(SubjectTier.PRODUCT_ADMIN, SubjectTier.TENANT_ADMIN)
                        .minAuthStrength(AuthStrength.OTP)
                        .stepUpWithin(SENSITIVE_STEP_UP)
                        .build(),
                PermissionPolicy.builder(ENVIRONMENT_CHANNEL_USE)
                        .audiences(ClientAudience.ADMIN_CONSOLE, ClientAudience.OPERATOR_CONSOLE)
                        .tiers(SubjectTier.PRODUCT_ADMIN, SubjectTier.PRODUCT_OPERATOR, SubjectTier.TENANT_OPERATOR)
                        .minAuthStrength(AuthStrength.OTP)
                        .stepUpWithin(SENSITIVE_STEP_UP)
                        .platformScoped()
                        .privilegedGrant(MAX_ENVIRONMENT_CHANNEL_GRANT)
                        .build(),
                PermissionPolicy.builder(ENVIRONMENT_CHANNEL_GRANT)
                        .audiences(ClientAudience.ADMIN_CONSOLE)
                        .tiers(SubjectTier.PRODUCT_ADMIN)
                        .minAuthStrength(AuthStrength.OTP)
                        .stepUpWithin(SENSITIVE_STEP_UP)
                        .platformScoped()
                        .build(),
                PermissionPolicy.builder(PLATFORM_ADMINISTER)
                        .audiences(ClientAudience.ADMIN_CONSOLE)
                        .tiers(SubjectTier.PRODUCT_ADMIN)
                        .minAuthStrength(AuthStrength.OTP)
                        .platformScoped()
                        .build()),
                List.of());
    }
}
