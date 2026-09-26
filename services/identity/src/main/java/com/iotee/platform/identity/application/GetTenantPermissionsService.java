package com.iotee.platform.identity.application;

import com.iotee.platform.identity.domain.TenantId;
import com.iotee.platform.identity.domain.TenantIdValidationException;
import com.iotee.platform.identity.port.in.AuthenticationFailedException;
import com.iotee.platform.identity.port.in.AuthorizationDeniedException;
import com.iotee.platform.identity.port.in.GetTenantPermissionsQuery;
import com.iotee.platform.identity.port.in.GetTenantPermissionsUseCase;
import com.iotee.platform.identity.port.in.InvalidQueryException;
import com.iotee.platform.identity.port.in.TenantPermissionsView;
import com.iotee.platform.identity.port.out.RoleAssignmentRepository;
import com.iotee.platform.identity.port.out.TokenSignatureVerifier;
import com.iotee.platform.identity.rbac.Permission;
import com.iotee.platform.identity.rbac.policy.AccessRequest;
import com.iotee.platform.identity.rbac.policy.AccessTokenClaims;
import com.iotee.platform.identity.rbac.policy.AccessTokenValidator;
import com.iotee.platform.identity.rbac.policy.ClientAudience;
import com.iotee.platform.identity.rbac.policy.Grant;
import com.iotee.platform.identity.rbac.policy.PolicyDecision;
import com.iotee.platform.identity.rbac.policy.PolicyDecisionPoint;
import com.iotee.platform.identity.rbac.policy.ResourceRef;
import com.iotee.platform.identity.rbac.policy.Subject;
import com.iotee.platform.identity.rbac.policy.SubjectTier;
import com.iotee.platform.identity.rbac.policy.TokenRejectedException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application service implementing {@link GetTenantPermissionsUseCase}
 * (ADR 0017 Decision 2, {@code application}). Formerly
 * {@code core.TenantPermissionsHandler}; same read, now authenticated and
 * authorized against the proposed identity ADR instead of the permit-all
 * {@code rbac.AbacContext} stub, which is no longer wired here (PF-C C3).
 *
 * <p>Framework-free apart from SLF4J (a logging facade, not a runtime
 * framework -- ADR 0017 Decision 4 only bans transport/framework libraries
 * from this layer, and every other driving component in this service logs
 * the same way): plain constructor injection, no Spring annotations
 * (Spring wiring lives only in {@code config}), so it is tested with a bare
 * JUnit test and no Spring context.
 *
 * <p>Per request, in order:
 * <ol>
 *   <li>{@code tenantId} and {@code subjectId} (the subject WHOSE
 *       permissions are being read) are validated exactly as before step
 *       C1; a failure is an {@link InvalidQueryException}.</li>
 *   <li>The caller's bearer token is verified: signature and algorithm via
 *       {@link TokenSignatureVerifier} (a vetted JOSE library, in an
 *       adapter -- never hand-rolled), then claims via whichever of
 *       {@code adminConsoleValidator} / {@code operatorConsoleValidator}
 *       matches the token's claimed tier's audience, per
 *       {@link SubjectTier#expectedAudience()}. Any rejection --
 *       absent, forged, expired, not-yet-valid, wrong issuer, wrong or
 *       forbidden audience, unknown client, too-long-lived, or a tier
 *       neither validator accepts -- is an
 *       {@link AuthenticationFailedException}. A gateway-supplied identity
 *       header is never consulted here or anywhere in this service: the
 *       ONLY input that can authenticate a caller is
 *       {@link GetTenantPermissionsQuery#bearerToken()} (ADR 0016 Decision
 *       4; the identity ADR's rejected alternative "trusting gateway-
 *       validated identity headers").</li>
 *   <li>The authenticated caller -- not the requested {@code subjectId} --
 *       must be authorized to read {@code tenantId}'s permission
 *       assignments: {@link PolicyDecisionPoint#decide} for
 *       {@link Permission#TENANT_READ} against
 *       {@link ResourceRef#tenant(String)}, deny by default. The caller's
 *       grant is bridged, on the fly, from its own RBAC role assignment in
 *       {@code tenantId} ({@link #callerGrants}): today's only grant
 *       source, since no persisted {@code Grant} store exists yet
 *       (tracked as blocked in {@code docs/identity-access-traceability.md}).
 *       This still enforces real revocation: {@code RbacRegistry.revoke}
 *       removes the assignment, {@link RoleAssignmentRepository#permissionsFor}
 *       then returns empty, no grant is bridged, and the very next request
 *       is denied ({@code DENY_NO_ACTIVE_GRANT}) -- proven by
 *       {@code GetTenantPermissionsServiceTest.revokedRoleAssignmentDeniesAccessOnTheNextRequest}.
 *       {@code PolicyDecisionPointTest.aRevokedGrantDeniesFromTheMomentOfRevocation}
 *       separately proves the underlying explicit-{@code Grant}-revocation
 *       path this same call relies on. A deny is an
 *       {@link AuthorizationDeniedException}; the decision's reason and any
 *       grant/rule id are logged for audit, never returned to the
 *       client.</li>
 *   <li>Only once permitted does the read proceed exactly as before: role
 *       assignments for the REQUESTED {@code subjectId} (which may be a
 *       different subject than the caller -- this is "what does X hold",
 *       not "what do I hold").</li>
 * </ol>
 *
 * <p>Role lookups are tenant-scoped throughout: a subject's role in one
 * tenant grants nothing when a different tenant is queried (ADR 0016
 * Decision 6), for both the caller's authorization and the requested
 * subject's returned permissions.
 */
public final class GetTenantPermissionsService implements GetTenantPermissionsUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(GetTenantPermissionsService.class);

    /** Synthetic bridge grants are never persisted; this id is a fixed, recognizable marker in audit logs. */
    private static final String BRIDGED_GRANT_ID = "rbac-bridge";
    private static final String BRIDGED_GRANTED_BY = "rbac-bridge";

    private final RoleAssignmentRepository roleAssignments;
    private final TokenSignatureVerifier tokenSignatureVerifier;
    private final AccessTokenValidator adminConsoleValidator;
    private final AccessTokenValidator operatorConsoleValidator;
    private final PolicyDecisionPoint policyDecisionPoint;
    private final Clock clock;

    public GetTenantPermissionsService(
            RoleAssignmentRepository roleAssignments,
            TokenSignatureVerifier tokenSignatureVerifier,
            AccessTokenValidator adminConsoleValidator,
            AccessTokenValidator operatorConsoleValidator,
            PolicyDecisionPoint policyDecisionPoint,
            Clock clock) {
        this.roleAssignments = Objects.requireNonNull(roleAssignments, "roleAssignments");
        this.tokenSignatureVerifier = Objects.requireNonNull(tokenSignatureVerifier, "tokenSignatureVerifier");
        this.adminConsoleValidator = Objects.requireNonNull(adminConsoleValidator, "adminConsoleValidator");
        this.operatorConsoleValidator = Objects.requireNonNull(operatorConsoleValidator, "operatorConsoleValidator");
        this.policyDecisionPoint = Objects.requireNonNull(policyDecisionPoint, "policyDecisionPoint");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TenantPermissionsView getTenantPermissions(GetTenantPermissionsQuery query) {
        Objects.requireNonNull(query, "query");

        TenantId tenantId;
        try {
            tenantId = TenantId.of(query.tenantId());
        } catch (TenantIdValidationException e) {
            throw new InvalidQueryException("tenantId failed validation", e);
        }
        String requestedSubjectId = query.subjectId();
        if (requestedSubjectId.isBlank()) {
            throw new InvalidQueryException("subjectId must not be blank", null);
        }

        Subject caller = authenticate(query.bearerToken());
        authorize(caller, tenantId);

        Set<Permission> rbacPermissions = roleAssignments.permissionsFor(tenantId, requestedSubjectId);
        List<String> granted = rbacPermissions.stream().map(Enum::name).sorted().toList();

        return new TenantPermissionsView(tenantId.value(), requestedSubjectId, granted);
    }

    /**
     * Verifies the bearer token's signature (via {@link #tokenSignatureVerifier}) and then its
     * claims (via whichever console validator matches the token's claimed tier), never trusting
     * anything else as the caller's identity.
     */
    private Subject authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            LOG.warn("Rejected request with no bearer token");
            throw new AuthenticationFailedException(
                    "authentication required", new TokenRejectedException(TokenRejectedException.Reason.MISSING_CLAIM));
        }

        AccessTokenClaims claims;
        try {
            claims = tokenSignatureVerifier.verify(bearerToken);
        } catch (TokenRejectedException e) {
            LOG.warn("Rejected bearer token: signature/claims parsing failed ({})", e.reason());
            throw new AuthenticationFailedException("authentication required", e);
        }

        // The claimed tier picks which console's validator applies (ADR: separate admin and
        // operator clients, audiences, sessions); a mismatch between the claimed tier and the
        // token's real audience is caught by the chosen validator's own audience check, not
        // papered over by trying the other validator instead.
        SubjectTier claimedTier = claims.tier();
        AccessTokenValidator validator = claimedTier != null && claimedTier.expectedAudience() == ClientAudience.OPERATOR_CONSOLE
                ? operatorConsoleValidator
                : adminConsoleValidator;

        try {
            return validator.validate(claims, clock.instant());
        } catch (TokenRejectedException e) {
            LOG.warn("Rejected bearer token: claims validation failed ({})", e.reason());
            throw new AuthenticationFailedException("authentication required", e);
        }
    }

    /** Deny-by-default: the caller must hold an active, tenant-scoped grant for {@code tenant.view}. */
    private void authorize(Subject caller, TenantId tenantId) {
        List<Grant> callerGrants = callerGrants(caller, tenantId);
        AccessRequest request = new AccessRequest(
                caller, Permission.TENANT_READ.key(), ResourceRef.tenant(tenantId.value()), Set.of(), callerGrants, clock.instant());

        PolicyDecision decision = policyDecisionPoint.decide(request);
        if (!decision.permitted()) {
            LOG.warn("Denied {} access to tenant {} permissions: {} (policy v{}, detail={})",
                    caller.subjectId(), tenantId.value(), decision.reason(), decision.policyVersion(), decision.detail());
            throw new AuthorizationDeniedException("access denied", null);
        }
    }

    /**
     * Bridges the caller's OWN RBAC role assignment in {@code tenantId} into a synthetic, always-
     * active {@link Grant} covering exactly the permissions that assignment confers -- today's only
     * grant source (no persisted {@code Grant} store exists yet). Empty when the caller holds no
     * role in this tenant, including immediately after {@code RbacRegistry.revoke}: the bridge is
     * recomputed from the role-assignment table on every call, so revocation takes effect on the
     * very next request, with no separate revocation step of its own.
     */
    private List<Grant> callerGrants(Subject caller, TenantId tenantId) {
        Set<Permission> callerRbacPermissions = roleAssignments.permissionsFor(tenantId, caller.subjectId());
        if (callerRbacPermissions.isEmpty()) {
            return List.of();
        }
        Set<String> permissionKeys = callerRbacPermissions.stream().map(Permission::key).collect(Collectors.toUnmodifiableSet());
        Grant bridged = new Grant(
                BRIDGED_GRANT_ID, caller.subjectId(), tenantId.value(), permissionKeys,
                null, null, null, null, null, BRIDGED_GRANTED_BY, null);
        return List.of(bridged);
    }
}
