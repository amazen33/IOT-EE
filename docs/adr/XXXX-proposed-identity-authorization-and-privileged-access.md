# ADR XXXX (proposed) -- Identity, authorization and privileged access: token and session model, policy decision semantics, entitlements vs permissions, environment-admin channels, device commands, firmware, payment webhooks

Status: proposed. Unnumbered draft per `docs/adr/README.md`; the number is
assigned at merge. Encodes direction given by the repository owner on
2026-09-26. Nothing here is complete until the acceptance criteria in
`docs/identity-access-traceability.md` pass; that document also records
which parts are implemented, which are blocked, and what the frozen Python
spec does differently.

Refines, without editing: ADR 0016 (tenancy and identity), ADR 0003 (M2
domain core, for the Python spec only), ADR 0006 (M5 firmware), ADR 0008
(M7 monetization), ADR 0018 Decision 6 (payment port), and the proposed
ADRs for roles and privileged environment access, and for service APIs and
payment providers. Answers two open questions of the roles ADR (grant
expiry, session termination on revocation) with proposals.

## Context

- `docs/requirements-addendum.md` sets the identity and command
  requirements. The owner restated and extended them on 2026-09-26:
  separate admin/operator OIDC clients, audiences, sessions and policies;
  short-lived access tokens; refresh-token rotation with reuse detection;
  server-side BFF sessions with Secure HttpOnly cookies and CSRF controls;
  MFA for administrators, step-up for sensitive changes, passkeys or
  security keys preferred where supported; rule-based authorization plus
  ABAC with explicit deny; environment-admin channels separate from device
  commands; firmware gated by entitlement, permission, compatibility and
  approval; entitlements, release flags, permissions and quotas kept
  separate; optional monetization with signed, idempotent webhooks.
- The Java platform has one service, `services/identity`. Before this
  change its role assignments were keyed by subject only, so a tenant
  admin in one tenant read as an admin of every tenant, and its ABAC hook
  was a permit-all stub. Its endpoints were unauthenticated before the
  C3 changes recorded below.
- Probes of the frozen Python spec (`spec/`, reference only) found
  behaviour that must not be carried into Java: a system admin implicitly
  holds every permission; a firmware manager in one tenant can put another
  tenant's device into a rollout; firmware has no tenant entitlement
  check; a command idempotency key reused after acknowledgement dispatches
  a new command; an acknowledgement is not bound to an authenticated
  device.

## Decision

1. **Tokens: each service validates them itself.** Admin and operator
   consoles use separate OIDC clients and API audiences (authorization
   code with PKCE). Every service validates the issuer, audience, client
   (`azp`), expiry, not-before, issued-at and maximum lifetime, and maps
   tier and tenant claims consistently (Tier 2 must carry exactly one
   tenant, Tier 1 none). An API rejects any token that also names a
   forbidden audience, so an operator token is rejected by admin APIs and
   the reverse, including multi-audience tokens. Signature and algorithm
   are verified by an adapter built on a vetted JOSE library against the
   issuer's published keys (`alg=none` and algorithm substitution
   rejected); no hand-written signature verification. Proposed default
   maximum access-token lifetime: 10 minutes; clock skew 30 seconds.
   Gateway identity headers stay advisory (ADR 0016 Decision 4).
2. **Sessions: server-side, behind the BFF.** Refresh tokens never reach
   the browser. The BFF keeps them in a server-side session store and
   gives the browser a session cookie that is `Secure`, `HttpOnly`,
   `SameSite=Lax` (or `Strict` for the admin console), host-only and
   separate per console. State-changing requests carry a CSRF token
   (synchronizer token), and the BFF checks `Origin`. The identity
   provider rotates refresh tokens and detects reuse; on a failed refresh
   or reported reuse the BFF destroys the session. Disabling an account or
   revoking a grant must end its active sessions.
3. **MFA: phishing-resistant preferred, TOTP the floor.** System and tenant
   administrators must use MFA. WebAuthn passkeys or security keys are the
   preferred factor wherever the identity provider and the user's device
   support them; a TOTP authenticator app is the minimum. SMS and email OTP
   are never the privileged default. Sensitive changes (role and grant
   changes, environment-channel grants, firmware approval) need a step-up
   within 15 minutes (proposal). Strength and freshness come from the
   token's `amr`/`acr` and `auth_time` through a provider-configured
   mapping. This makes the addendum's "TOTP required, WebAuthn allowed"
   concrete as "WebAuthn preferred, TOTP minimum".
4. **Authorization: one decision semantics, owned per service.** Each
   service evaluates its own versioned policy data with a framework-free
   decision point behind a port (ADR 0013: copied, not shared; OPA may
   replace it later behind the same port). Semantics:
   - deny by default: a permission without policy data is denied;
   - an explicit deny wins over every grant and every tier, system admins
     included;
   - attributes checked before any grant is considered: client audience
     against tier, tier, tenant (a Tier 2 subject only in its own tenant),
     resource shape (tenant vs platform), device named for device-scoped
     permissions, tenant entitlement, authentication strength and step-up
     freshness;
   - then an active grant (not revoked, not expired, started) must cover
     the permission, the tenant (or platform), the device and the
     environment;
   - a Tier 1 subject has no implicit access to tenant resources; acting
     in a tenant needs a grant naming that tenant, which is the explicit,
     audited cross-tenant path ADR 0016 leaves open;
   - grants never change a subject's tier;
   - every decision records its reason, the grant or rule involved and the
     policy version, for audit.

   Delegation: no self-grants; operators never grant; a tenant admin
   delegates only within its own tenant, only to that tenant's subjects,
   and never beyond an active grant it holds itself (permissions, devices,
   environments, lifetime). Enforcement belongs in every service, API and
   channel (REST, gRPC, WebSocket, MQTT command paths), not only at the
   gateway; UI hiding is never authorization.
5. **Permissions, entitlements, release flags and quotas are four separate
   concepts.**
   - A *permission* is what a subject may do, conferred by grants.
   - An *entitlement* is what a tenant has been enabled for, set by a
     system admin, independent of billing. It is an input to the decision
     point, never a grant.
   - A *release flag* says whether a code path is rolled out. It is
     operational, owned by engineering, and never an authorization input.
   - A *quota* bounds how much a tenant may use. It is enforced by the
     owning service and never turns a permission on or off.

   *Essential capabilities* are telemetry ingestion and viewing, alarm
   raising and acknowledgement, in-scope device commands, security
   functions (authentication, revocation, audit) and incident reporting.
   They are never gated by an entitlement, release flag, quota or billing
   state. Policy data that tries to gate one is rejected when it is built.
6. **Environment-admin channels are a separate, platform-scoped permission
   family.** They are never tenant-scoped and never pass through device
   commands. Only system admins grant or revoke them, and each grant needs:
   - a just-in-time approval reference;
   - an expiry at most 4 hours ahead (proposal);
   - a grantor with MFA and a fresh step-up.

   Access is through a bastion or gateway using short-lived credentials
   that expire no later than the grant (for example SSH certificates). The
   mechanism is open. Revocation blocks new sessions immediately and must
   terminate open sessions (proposal, answering the roles ADR's open
   question). Every grant, use and revocation is audited. An environment
   admin keeps its own tier and never gains platform administration.
7. **Device property commands follow stricter replay rules than the spec.**
   Commands are server-mediated, from an allowlist of properties, within
   the operator's tenant and device scope, with the audience, MFA and
   expiry checks above. Each command is:
   - schema and range validated, rate-limited and version-checked
     (optimistic concurrency);
   - audited, with a TTL, and expired when stale.

   The idempotency key is bound to tenant, device, property and a hash of
   the desired value, and retained for longer than the TTL:
   - reuse with a different value is a conflict and is rejected;
   - reuse after completion, failure or expiry returns the original
     outcome and never dispatches again.

   An acknowledgement counts only when it comes over the device's
   authenticated channel (mTLS identity or a signed acknowledgement
   carrying the command id and a nonce). An accepted command is not proof
   that the device applied it.
8. **Firmware needs four checks, at submission and again at dispatch:** the
   tenant's firmware entitlement (set by a system admin), the user's
   firmware permission, device scope and hardware compatibility, and a
   scoped approval. Revocation blocks queued and new dispatches and pauses
   rollouts where the device supports it. It does not claim to stop a
   flash already running.
9. **Payment webhooks are verified, idempotent and reconciled.** This
   extends the service-API and payment ADR:
   - A webhook adapter verifies the provider signature over the raw body
     with a timestamp tolerance, before parsing.
   - It records the provider event id in a dedupe table in the same
     transaction as the effect, so a duplicate delivery is harmless.
   - It tolerates out-of-order delivery by applying state transitions,
     not last-write-wins.
   - A scheduled reconciliation compares provider records with the
     platform ledger and reports differences for human review; it never
     edits closed periods (ADR 0008 reversal-only correction).

   Checkout is provider-hosted. The platform never stores or processes
   card data unless the organization records an explicit acceptance of
   the corresponding PCI scope. Payment provider, merchant country,
   currencies and tax rules remain open inputs. Monetization stays
   optional, default-off and downstream of finalized usage events
   (ADR 0018); telemetry, alarms and commands never wait on it.

## Consequences

- `services/identity` gains tenant-scoped role assignments (the
  cross-tenant defect is fixed and pinned by tests) and a framework-free
  `rbac.policy` package covering Decisions 1 (claims only), 3, 4, 5 and 6
  (grant rules only), with unit tests.
- `services/identity`'s vendor denylist now includes `org.keycloak..` and
  `com.stripe..` (ADR 0016 Decision 5, ADR 0017 Decision 4).

**Addendum, 2026-09-26 (PF-C C3):** the paragraph above originally read
"None of it is enforced at an endpoint yet: the service is still
unauthenticated and still wires the permit-all ABAC stub... Wiring needs
the signature-verifying adapter... and is PF-C C3 work." That is no
longer true and this addendum supersedes it in place, per
`docs/adr/README.md` (this ADR is still an unnumbered proposed draft, not
yet ratified, so amending it directly -- clearly dated -- is preferred
here over drafting a second ADR to supersede a decision that was never
ratified in the first place):

- Both of `services/identity`'s live endpoints (REST and gRPC) now
  require and verify a bearer token before authorizing the caller. The
  permit-all `AbacContext.alwaysPermit()` bean is removed from
  `config.IdentityServiceConfig`.
- Decision 1's signature-verifying adapter now exists:
  `adapter.out.jwt.NimbusTokenSignatureVerifier`, built on Nimbus JOSE +
  JWT (a vetted JOSE library), confined to that one class by the
  vendor-SDK denylist (`com.nimbusds..` added alongside `org.keycloak..`
  and `com.stripe..`). It pins one algorithm against a configurable JWKS
  URI, rejecting `alg=none` and algorithm substitution by construction.
  It now compiles against Nimbus 9.37.4 in the local identity Maven gate
  (225 tests pass), but has not run against a real or realistic-fake
  identity provider. Signature and key-selection behavior remain
  unverified end to end until a JWKS integration test passes.
- Authorization for the caller is bridged live from the existing
  `RoleAssignmentRepository` (today's only grant source; there is still
  no persisted `Grant` store, so Decision 4's grant-expiry/device/
  environment scoping is proven separately by `PolicyDecisionPointTest`
  but is inert for this endpoint today).
- See `docs/identity-access-traceability.md` for the exhaustive negative
  tests (absent/forged/expired/wrong-issuer/wrong-audience/
  forbidden-audience/cross-tenant/revoked tokens, and the
  gateway-header-alone-grants-nothing cases at both transports) and for
  which of this addendum's claims are unit-tested versus CI-only versus
  still unverified by any test run in this sandbox.
- What this addendum does NOT change: BFF sessions (Decision 2), MFA
  enrollment (Decision 3), device commands (Decision 7), firmware
  (Decision 8) and payment webhooks (Decision 9) still have no Java code.
- Device commands, firmware, entitlement management, BFF sessions,
  environment-channel credentials and payment webhooks have no Java code.
  Their acceptance criteria are recorded as blocked, not passed.
- The frozen Python spec is not changed. Its divergences are recorded as
  findings so the Java implementation does not reproduce them.

## Alternatives rejected

- **Trusting gateway-validated identity headers.** Rejected by ADR 0016
  Decision 4; restated because the admin/operator split depends on it.
- **A system-admin "all permissions" shortcut** (as in the Python spec).
  Rejected: it defeats explicit deny and makes cross-tenant access
  implicit.
- **Entitlements as permissions, or billing state as feature access.**
  Rejected: it lets a billing or feature switch disable essential
  functions, and ADR 0008 already keeps monetization out of feature access.
- **Hand-written JWT signature verification.** Rejected in favour of a
  vetted library in an adapter.
- **Environment-admin access via device commands or tenant roles.**
  Rejected: different blast radius, different approvers.

## Open questions for the owner

- Identity provider and JOSE library (ADR 0016 names Keycloak as the first
  provider); access-token lifetime and step-up window defaults.
- Who approves an environment-channel grant (a second system admin?), and
  the credential mechanism (SSH certificates, bastion product).
- Whether system operators get their own console and client (the roles ADR
  asks this too).
- The initial entitlement catalog (firmware management first) and where
  release flags live.
- Payment provider, merchant country, currencies, tax and invoicing
  ownership, and the formal PCI scope decision.
