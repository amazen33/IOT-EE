# Identity, authorization and access: requirements vs implementation

Status as of 2026-09-26. Compares the owner's identity, authorization,
tenant-operator, device-command, environment-admin, entitlement and
monetization requirements (`docs/requirements-addendum.md`, restated
2026-09-26) with what the repository actually contains. Decisions are in
`docs/adr/XXXX-proposed-identity-authorization-and-privileged-access.md`
(proposed).

**Rule for this document:** a control is listed as present only when code
and a test prove it. The identity permission read is now enforced at its
REST and gRPC boundaries; WebSocket, MQTT and other service boundaries
are not covered by this module.

## Status legend

| Status | Meaning |
| --- | --- |
| **Java: unit-tested** | Framework-free Java in `services/identity` with passing unit tests. Not wired to an endpoint unless stated. |
| **Locally verified** | Java test passed in the local identity Maven gate; this does not prove a real IdP/JWKS integration. |
| **CI only** | Test or environment proof unavailable locally; CI or a real integration environment remains the check. |
| **Spec only** | The frozen Python reference (`spec/`) implements and tests it; no Java. |
| **Spec gap** | A probe of the frozen spec shows it violates the requirement. The spec is frozen and is not changed; the Java implementation must not copy it. |
| **Not implemented** | No code anywhere. |
| **Blocked** | Needs something not available here: an identity provider, a vetted library, a broker, a device, a bastion, a payment provider or an owner decision. |

## What changed in this step

- **Cross-tenant defect fixed** in the only running Java endpoint:
  `RbacRegistry` and `RoleAssignmentRepository` were keyed by subject only,
  so `GET /tenants/{any}/permissions?subjectId=synthetic-subject-admin`
  returned `TENANT_MANAGE` for every tenant. Assignments are now keyed by
  tenant and subject.
- **`services/identity/.../rbac/policy/`** (framework-free, JDK only):
  - `PolicyDecisionPoint`: deny by default, explicit deny wins, ABAC
    attributes.
  - `GrantAuthority`: delegation and environment-channel grants.
  - `AccessTokenValidator`: claim validation.
  - `IdentityPolicyV1`: this service's version-1 policy data.
- **Vendor bans** `org.keycloak..` and `com.stripe..` added to the identity
  service's ArchUnit denylist, with a planted-violation fixture.

### PF-C C3 (this step): both live endpoints now authenticate and authorize

- **The permit-all `rbac.AbacContext` stub is removed from live wiring.**
  `config.IdentityServiceConfig` no longer has an `abacContext()` bean; the
  class still exists (test-support only) but nothing in production
  references it.
- **New outbound port** `port.out.TokenSignatureVerifier` and its
  implementation `adapter.out.jwt.NimbusTokenSignatureVerifier` (Nimbus
  JOSE + JWT, a vetted JOSE library, confined to that one class by the
  vendor-SDK denylist, ADR 0016 Decision 5). Configurable issuer, JWKS URI,
  algorithm (default `RS256`, pinned -- `alg=none` and algorithm
  substitution rejected by construction, not by a runtime check), claim
  names and clock skew/max-lifetime; nothing hardcoded.
- **`application.GetTenantPermissionsService` rewritten**: every call now
  (1) validates `tenantId`/`subjectId` as before, (2) verifies the caller's
  bearer token's signature via the port above, (3) validates its claims
  via whichever of two `AccessTokenValidator`s (admin console / operator
  console) matches the token's claimed tier, (4) authorizes the
  authenticated caller -- never the requested `subjectId` -- via
  `PolicyDecisionPoint.decide` for `tenant.view`, with the caller's grant
  bridged live from its own RBAC role assignment (today's only grant
  source; no persisted `Grant` store exists yet), then (5) returns the
  REQUESTED subject's permissions exactly as before. A gateway-supplied
  identity header is read nowhere in this path (ADR 0016 Decision 4); the
  query record's only identity-bearing field is the bearer token.
- **Both transports updated**: the REST controller reads `Authorization:
  Bearer <token>`; a new `adapter.in.grpc.BearerTokenServerInterceptor`
  reads the gRPC `authorization` metadata entry into `Context`, registered
  on `GrpcServerRunner`. Two new `port.in` exceptions,
  `AuthenticationFailedException` (401 / `UNAUTHENTICATED`) and
  `AuthorizationDeniedException` (403 / `PERMISSION_DENIED`), mirror
  `InvalidQueryException`'s pattern; both advices/mappers never echo the
  policy decision's reason, grant id, or `TokenRejectedException.Reason`
  to the client.
- **`com.nimbusds..` added** to the identity service's vendor-SDK
  ArchUnit denylist, with a planted-violation fixture
  (`ViolatingJwtLibraryUsageClass`); the "vendor-SDK-outside-adapters rule
  is currently vacuous" test is retired and replaced with one asserting
  the OPPOSITE now that `NimbusTokenSignatureVerifier` is a real,
  live-wired vendor-backed adapter.
- **New negative tests** in `application.GetTenantPermissionsServiceTest`
  (framework-free, real `AccessTokenValidator`/`PolicyDecisionPoint`, a
  hand-written `TokenSignatureVerifier` fake standing in for Nimbus):
  absent token, forged/unrecognized signature, expired token, wrong
  issuer, wrong audience, forbidden (interchanged) audience, cross-tenant
  token, revoked role assignment (this endpoint's real revocation path),
  and a caller-authority-never-comes-from-the-requested-subject-id case.
  Mirrored, less exhaustively, at the transport layer
  (`TenantPermissionsGrpcServiceTest`, `TenantPermissionsControllerTest`),
  including a spoofed gateway-identity-header case at both transports
  proving the header is never read.

**Local verification after reconciliation (2026-09-26):**
`mvn -f pom.xml verify` passes, including 228 identity tests with no
failures or errors. The gate compiles `NimbusTokenSignatureVerifier`
against the real Nimbus 9.37.4 library and executes the application,
REST/gRPC wiring and architecture tests. The prior authoring sandbox
could not reach Maven Central; that earlier limitation is superseded by
this local run. An in-process JWKS test now exercises signed-token
acceptance, signature/algorithm rejection and refresh on a new key ID.
It does not cover a real IdP or a signed-token REST/gRPC round trip.

Prior step's local verification (unchanged, for the record): the
framework-free sources and their tests were compiled with `javac
--release 17` and run against a minimal JUnit 5 stand-in. 94 tests passed.
Ten deliberate mutations were each caught: removing the cross-tenant,
entitlement, revocation, explicit-deny, audience, delegation,
privileged-grantor, forbidden-audience or step-up check, or reverting
tenant-scoped assignments. Spring, gRPC and ArchUnit tests run only in CI
(`mvn -f pom.xml verify`).

## Requirement-by-requirement

### 1. OIDC/SSO: separate admin and operator clients, audiences, sessions and policies; services validate tokens themselves

| Part | Evidence | Status |
| --- | --- | --- |
| Separate audiences; operator token rejected by admin API and the reverse (incl. multi-audience tokens) | `AccessTokenValidatorTest`: `anOperatorTokenIsRejectedByTheAdminApi`, `anAdminTokenIsRejectedByTheOperatorApi`, `aTokenCarryingBothAudiencesIsRejectedByBoth`, `aTokenFromTheOperatorClientIsRejectedByTheAdminApiEvenWithTheAdminAudience`, `anOperatorTierTokenIssuedToTheAdminAudienceIsStillRejected` | Java: unit-tested |
| Tier and audience must agree at decision time | `PolicyDecisionPointTest.anOperatorWhoPresentsAnAdminConsoleSessionIsDenied`, `anAdminConsolePermissionIsNotExercisableFromTheOperatorConsole` | Java: unit-tested |
| Issuer, expiry, not-before, issued-at, client (`azp`), tier/tenant consistency | `AccessTokenValidatorTest` (issuer, skew, missing claims, tenant claims) | Java: unit-tested |
| Signature and algorithm verification against issuer keys | `NimbusTokenSignatureVerifierJwksTest` runs the real Nimbus adapter against a local HTTP JWKS: valid RS256, unsigned, HS256 substitution, wrong RSA signature and a new key ID after rotation. It also passes verified claims to `AccessTokenValidator` and proves expiry is rejected there. | Locally verified with synthetic issuer; real IdP and transport round trip pending |
| Endpoints require a validated token | Both `services/identity` REST and gRPC endpoints now require a bearer token; the permit-all `AbacContext.alwaysPermit()` bean is removed from `config.IdentityServiceConfig`. Application-layer fake-verifier tests exercise absent/forged/expired/wrong-issuer/wrong-audience/forbidden-audience/cross-tenant/revoked cases. Spring/gRPC tests pass for unauthenticated paths, but not a successfully signed token. | Locally verified for current test cases; signed-token integration pending |
| Separate sessions per console | No BFF exists | **Not implemented** |
| Spec | `spec/gateway/auth.py`: opaque token map, no issuer or audience concept | Spec gap (by design: IdP deferred) |

### 2. Short-lived access tokens; refresh rotation and reuse detection; BFF sessions, Secure HttpOnly cookies, CSRF

| Part | Evidence | Status |
| --- | --- | --- |
| Maximum access-token lifetime enforced | `AccessTokenValidatorTest.longLivedAccessTokensAreRejected` | Java: unit-tested |
| Refresh-token rotation and reuse detection | Identity-provider behaviour; no IdP configured | **Blocked** |
| BFF server-side session store; session destroyed on refresh failure/reuse | No BFF | **Not implemented** |
| `Secure`, `HttpOnly`, `SameSite`, host-only cookies; CSRF token + `Origin` check | No BFF | **Not implemented** |
| Account disable / grant revoke ends active sessions | No sessions exist | **Blocked** |

### 3. MFA/OTP for administrators, step-up for sensitive changes, passkeys preferred

| Part | Evidence | Status |
| --- | --- | --- |
| Minimum authentication strength per permission | `PolicyDecisionPointTest.anAdminWithoutMfaCannotManageRoles` | Java: unit-tested |
| Step-up freshness for sensitive changes; absent or future `auth_time` denies | `PolicyDecisionPointTest.aSensitiveChangeNeedsARecentStepUp`; `GrantAuthorityTest.tenantRoleDelegationRequiresMfaAndFreshStepUpEvenWithAnActiveGrant`; `GrantAuthorityTest.aChannelGrantNeedsTheGrantorsMfaAndARecentStepUp` | Java: unit-tested |
| `amr` mapping; phishing-resistant factor recognized; unknown methods (e.g. SMS) never count as MFA; absent `auth_time` stays unknown | `AccessTokenValidatorTest.thePhishingResistantFactorIsRecognisedButMissingAuthTimeRemainsUnknown`; `AccessTokenValidatorTest.futureAuthTimeAndInconsistentClaimTimesAreRejected` | Java: unit-tested |
| Enrolment, recovery, WebAuthn/TOTP at the IdP | No IdP | **Blocked** |

### 4. Rule-based authorization plus ABAC; explicit deny wins; enforced in every service/API/channel

| Attribute | Evidence (`PolicyDecisionPointTest` unless noted) | Status |
| --- | --- | --- |
| Deny by default | `aPermissionWithoutPolicyDataIsDeniedByDefault`, `noGrantMeansDeny` | Java: unit-tested |
| Explicit deny wins, incl. over system admins | `anExplicitDenyWinsOverAnActiveGrant`, `anExplicitDenyAlsoBindsSystemAdmins` | Java: unit-tested |
| Tenant | `aTenantSubjectIsDeniedInAnotherTenantEvenWithAGrantNamingThatTenant`, `aTenantGrantDoesNotApplyToAnotherTenantsDevices`, `aSystemAdminHasNoImplicitAccessToTenantResources`; `RbacRegistryTest.aRoleInOneTenantGrantsNothingInAnotherTenant`; `GetTenantPermissionsServiceTest.aSubjectsRoleInOneTenantGrantsNothingWhenAnotherTenantIsQueried` | Java: unit-tested |
| Tenant, through the running service (caller authenticated and authorized) | `GetTenantPermissionsServiceTest.crossTenantTokenIsDenied` | Java: unit-tested |
| Tenant, through the running REST/gRPC endpoints | `TenantPermissionsControllerTest` and `TenantPermissionsGrpcServiceTest` locally prove unauthenticated-path status codes (401 / `UNAUTHENTICATED`), not a successful cross-tenant 200-vs-403 signed-token round trip. | Locally verified for unauthenticated paths; integration proof pending |
| Role / tier (grants never widen a tier) | `anEnvironmentChannelGrantNeverConfersPlatformAdministration` | Java: unit-tested |
| Resource shape (tenant vs platform) | `anEnvironmentChannelGrantIsLimitedToItsEnvironmentsAndNeverTouchesTenantResources` | Java: unit-tested |
| Environment | same test | Java: unit-tested |
| Device assignment | `aDeviceOutsideTheOperatorsAllowlistIsDenied`, `aDeviceScopedPermissionRequiresANamedDevice` | Java: unit-tested |
| Entitlement | `firmwareIsDeniedWithoutTheTenantEntitlementEvenWithThePermission` | Java: unit-tested |
| MFA | see section 3 | Java: unit-tested |
| Expiry and revocation | `anExpiredGrantIsDenied`, `aRevokedGrantDeniesFromTheMomentOfRevocation` | Java: unit-tested |
| Decision audit data (reason, grant/rule id, policy version) | `anActiveGrantPermitsAndTheDecisionNamesItAndThePolicyVersion` | Java: unit-tested (no audit store) |
| Enforced in every service/API/channel | Only `services/identity` exists. Its two live endpoints (REST + gRPC) now authenticate and authorize the caller (see the "Endpoints require a validated token" row above); no other service exists yet | **Not implemented** (all other services) |
| Spec | `spec/domain_core/rbac.py`: `Principal.has_permission` returns True for every permission for a system admin; no explicit deny | **Spec gap** |

### 5. Tenant operators change only allowlisted device properties in scope, via audited, expiring, idempotent, server-mediated commands with device acknowledgement

| Part | Evidence | Status |
| --- | --- | --- |
| Allowlisted properties, tenant and device scope, scope expiry, cross-tenant and unassigned device rejected, system admins cannot dispatch directly | `spec/tests/test_domain_core_commands.py` (e.g. `test_forbidden_property_rejected`, `test_cross_tenant_device_access_rejected`, `test_unassigned_device_rejected`, `test_expired_scope_rejected`) | Spec only |
| Same semantics as policy (device allowlist, essential command permission) | `PolicyDecisionPointTest.aDeviceOutsideTheOperatorsAllowlistIsDenied`, `essentialCapabilitiesStayAvailableWithNoEntitlementsAtAll` (fixture permissions) | Java: unit-tested (policy only) |
| Stale commands expire; expired commands are not delivered | `test_stale_command_expires_and_is_not_reused`, `test_drain_on_reconnect_drops_expired_commands` | Spec only |
| Redelivery of the same command id on reconnect skipped | `test_redelivering_the_same_command_id_is_skipped_on_a_second_drain` | Spec only |
| **Replay of an idempotency key after acknowledgement** | Probe: `CommandStore.dispatch` with the same key after `acknowledge` creates a new command | **Spec gap** |
| **Same key, different value** | Probe: silently returns the first command instead of rejecting a conflict | **Spec gap** |
| **Acknowledgement bound to an authenticated device** | Probe: `acknowledge(command_id, now)` takes no device identity or nonce | **Spec gap** |
| Audit record per command; schema/range validation; optimistic concurrency; rate limits; revocation of a scope mid-queue | None | **Not implemented** |
| Java device-command service | None | **Not implemented** |

### 6. Environment-admin (public VM) channels, separate from device commands

| Part | Evidence (`GrantAuthorityTest` unless noted) | Status |
| --- | --- | --- |
| Only system admins grant; tenant admins, operators and self-grants rejected | `aTenantAdminCannotGrantAnEnvironmentChannelEvenToItsOwnOperators`, `operatorsCannotGrantAnything`, `selfGrantsAreDeniedForEveryTier` | Java: unit-tested |
| Just-in-time approval reference and bounded expiry (max 4 h) | `aChannelGrantNeedsApprovalAndABoundedExpiry`, `aSystemAdminMayGrantAJustInTimeChannelWithApprovalAndExpiry` | Java: unit-tested |
| Grantor MFA and fresh step-up | `aChannelGrantNeedsTheGrantorsMfaAndARecentStepUp` | Java: unit-tested |
| Platform-scoped, never tenant-scoped; separate from device commands | `aChannelGrantCannotBeTenantScoped`; `PolicyDecisionPointTest.anEnvironmentChannelGrantIsLimitedToItsEnvironmentsAndNeverTouchesTenantResources` | Java: unit-tested |
| Revocation ends access immediately (policy level) | `revokingAChannelGrantEndsAccessImmediately`; `PolicyDecisionPointTest.anExpiredEnvironmentChannelGrantIsDenied` | Java: unit-tested |
| An environment admin never becomes a system admin | `anEnvironmentAdminNeverBecomesASystemAdmin`; `PolicyDecisionPointTest.anEnvironmentChannelGrantNeverConfersPlatformAdministration` | Java: unit-tested |
| Approval record verified (exists, approved by a second person, unexpired) | Approval workflow and store do not exist | **Not implemented** |
| Short-lived credentials, bastion/gateway, open-session termination, audit store | None | **Blocked** (mechanism and bastion undecided) |
| Spec | None | Not implemented |

### 7. Firmware: tenant entitlement AND user permission AND device compatibility AND scoped approval

| Part | Evidence | Status |
| --- | --- | --- |
| Entitlement required even with the permission, including for system admins | `PolicyDecisionPointTest.firmwareIsDeniedWithoutTheTenantEntitlementEvenWithThePermission`, `firmwareNeedsTheEntitlementEvenForASystemAdminWithATenantGrant`, `firmwareNeedsTheEntitlementAndThePermissionAndTheDevice` | Java: unit-tested (fixture permission; no firmware service) |
| Signed artifacts, provenance, canary/broad rings, auto-rollback | `spec/tests/test_firmware_*` | Spec only |
| **Tenant entitlement** | Probe: `FirmwareRollout` checks only `firmware.manage`; no entitlement input exists | **Spec gap** |
| **Tenant scoping of rollout devices** | Probe: a tenant-A firmware manager assigned a tenant-B device | **Spec gap** |
| Device hardware compatibility; scoped approval; recheck at dispatch; system-admin entitlement management | None | **Not implemented** |
| UI hiding is not authorization | Requirement recorded in the ADR; no UI exists | Not applicable yet |

### 8. Entitlements, release flags, permissions and quotas are separate; essential functions cannot be disabled

| Part | Evidence | Status |
| --- | --- | --- |
| An essential capability cannot be gated by an entitlement (rejected when the policy is built) | `PolicyDecisionPointTest.anEssentialCapabilityCannotBeDeclaredWithAnEntitlementGate` | Java: unit-tested |
| Telemetry, alarms and in-scope commands stay available with no entitlements | `essentialCapabilitiesStayAvailableWithNoEntitlementsAtAll` | Java: unit-tested (fixture permissions) |
| Entitlements are tenant facts, separate from grants | `AccessRequest.tenantEntitlements` vs `Grant.permissions` | Java: unit-tested |
| Monetization flag is not feature access | `spec/billing/flags.py` (no `is_feature_entitled`); ADR 0008 | Spec only |
| Release flags, quotas, an entitlement catalog and its management API | None; `tenant.manage_entitlements` exists in the spec catalog with no model | **Not implemented** |

### 9. Monetization: optional, downstream of finalized usage; provider port; hosted checkout; signed, idempotent webhooks; reconciliation; no card data

| Part | Evidence | Status |
| --- | --- | --- |
| Default off, per-tenant override, disabled mode has no side effects | `spec/tests/test_billing_flag_matrix.py` | Spec only |
| Write-once period closure, reversal-only correction, duplicate closure rejected | `spec/tests/test_billing_closure.py` | Spec only |
| Billing isolated from core contexts | `spec/scripts/check.py` `_check_billing_isolation` | Spec only |
| Consumes **finalized usage events** | Spec meters the active device count from `DeviceRegistry`, not finalized `UsageMetered` events as ADR 0018 requires | **Spec gap** (documented divergence) |
| Payment SDK confined to its adapter | `IdentityFrameworkFreedomArchitectureRulesTest.noVendorSdkOutsideAdaptersRuleCatchesIdentityProviderAndPaymentSdks` (identity service only) | Locally verified |
| Provider port, hosted checkout, webhook signature verification, dedupe, reconciliation | None | **Not implemented**; provider choice **Blocked** (open input) |
| No card data stored or processed | No payment code exists, so vacuously true; no test can prove it yet | Not applicable yet |

## Required negative tests

| Case | Proven now | Still blocked (acceptance criterion) |
| --- | --- | --- |
| **Token interchange** | Claims level (`AccessTokenValidatorTest`, 5 cases), decision level (`PolicyDecisionPointTest`, 2 cases), and application level (`GetTenantPermissionsServiceTest`) reject absent, forged, expired, wrong-issuer, wrong-audience and forbidden-audience tokens before authorization. REST/gRPC unauthenticated and spoofed-gateway-header tests pass locally. The real Nimbus adapter rejects unsigned and substituted-algorithm tokens against a synthetic JWKS. | Reject real IdP-signed operator tokens at admin REST/gRPC endpoints and vice versa through a signed-token round trip. |
| **Cross-tenant access** | Role lookup, service and policy (`RbacRegistryTest`, `GetTenantPermissionsServiceTest.crossTenantTokenIsDenied`, `PolicyDecisionPointTest`) pass locally; REST/gRPC prove the unauthenticated path only. | With PostgreSQL RLS (ADR 0016), a query under tenant A's `app.tenant_id` returns no tenant-B rows even when application checks are bypassed; a missing `app.tenant_id` fails closed. A cross-tenant 200-vs-403 round trip through real REST/gRPC endpoints needs a signed token. |
| **Authorization revocation** | Revoked and expired grants deny from the revocation instant; a tenant admin whose own grant is revoked cannot delegate (`GrantAuthorityTest.aTenantAdminWhoseOwnGrantWasRevokedCanNoLongerDelegate`); now also `GetTenantPermissionsServiceTest.revokedRoleAssignmentDeniesAccessOnTheNextRequest`, proving that `InMemoryRoleAssignmentRepository.revoke()` denies the very next call to this endpoint through the real authorization bridge | Revocation during an active BFF session ends that session on the next request; revocation blocks commands already queued for dispatch. Needs sessions and a command service. |
| **Stale or replayed commands** | Spec: expiry and redelivery skip | In the Java command service: reusing an idempotency key after completion returns the original outcome without dispatching; the same key with a different value returns 409; an expired command is never delivered after reconnect; an acknowledgement not from the device's authenticated channel is rejected. The spec fails the first two and has no device binding. |
| **Privilege escalation** | Delegation beyond the grantor's permissions, devices, environments or lifetime; cross-tenant delegation; self-grants; operator grants; tier widening (`GrantAuthorityTest`, 9 cases) | An end-to-end role-assignment API that returns 403 for each case and writes an audit record. |
| **Firmware entitlement bypass** | Policy denies without the entitlement, including for system admins (`PolicyDecisionPointTest`, 3 cases) | Firmware service rejects submission and dispatch without the entitlement; entitlement revoked mid-rollout blocks queued dispatches; device outside tenant or incompatible hardware rejected. The spec fails the entitlement and tenant-scope cases. |
| **VM-access revocation** | Policy level (`GrantAuthorityTest.revokingAChannelGrantEndsAccessImmediately`) | Revoking a grant invalidates issued short-lived credentials and terminates an open bastion session within a stated bound; expiry does the same without action. Needs the bastion and credential mechanism. |
| **Payment webhook duplication** | Nothing exists | The same provider event delivered twice (and concurrently) produces one effect; an unsigned, wrongly signed or stale-timestamp webhook is rejected before parsing; out-of-order events converge; reconciliation reports a planted mismatch. Needs the payments service and a provider decision. |

## Known limits of this step

- **Authentication and authorization are wired on both live transports;
  signed-token transport integration remains unverified.** The Nimbus
  adapter passes an in-process rotating-JWKS test for signed acceptance,
  unsigned/substituted/forged rejection and a new key ID. A real IdP,
  retired-key behavior and signed-token REST/gRPC round trips remain.
- **Only one grant source exists.** The caller's authorization is bridged
  live from its own RBAC role assignment (`RoleAssignmentRepository`);
  there is no persisted `Grant` store yet, so a `Grant`'s `notBefore`,
  `expiresAt`, `deviceIds` and `environments` scoping are inert for this
  endpoint, even though that mechanism is separately proven correct by
  `PolicyDecisionPointTest`. Revocation for this endpoint today means
  removing the underlying role assignment, proven by
  `GetTenantPermissionsServiceTest.revokedRoleAssignmentDeniesAccessOnTheNextRequest`.
- `mvn -f pom.xml verify` passes locally: 228 identity tests,
  zero failures or errors. It does not replace a real IdP test,
  full reactor gate or production security review.
- **No BFF, sessions, MFA-issuing IdP, or persisted audit/decision store
  exist.** This step only wires authentication and authorization for the
  one existing read-only endpoint; nothing here should be read as
  covering session management, MFA enrollment, or audit evidence.
- Policy data is in code (`IdentityPolicyV1`, version 1). A reviewed store
  with system-admin approval is future work.
- The device, firmware and telemetry permissions in `PolicyFixtures` are
  test fixtures. They prove the evaluator's semantics, not a device or
  firmware service.
- **The frozen Python spec is unchanged**, and its gaps above remain.
