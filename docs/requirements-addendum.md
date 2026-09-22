# Requirements addendum — tenant administration and configurable integration

Status: proposed implementation requirements accepted for roadmap planning; no implemented capability or verified TB feature parity is implied.

## Interfaces and migration sequence

REST over HTTPS is the frontend default through APISIX/BFF. Use gRPC for synchronous calls between separately deployed services, in-process interfaces for modular-monolith modules, Kafka for asynchronous domain events, and MQTT for devices. Use one authorized multiplexed WebSocket connection per browser for updates.

Keep Migration Studio foundation in M1 and configurable connectors/shadow-parity tooling in M3. Make full production legacy import, reconciliation, approval, and cutover the final stage M9, after M8 Deployment Studio. Earlier stages use synthetic inputs or explicitly approved limited samples/shadow reads only. Configuration covers supported source schemas, hierarchy depths, field mappings, units, timestamps, relationships, and sandboxed supported transformations; unknown protocols/custom executable rules can require reviewed adapters. Version, validate, preview, approve, and roll back configurations. M9 requires scoped inventory coverage, counts/hashes and semantic reconciliation, delta catch-up, preserved rule/report parity, rollback rehearsal, acceptance, and authorized cutover. Never automatically import secrets.

## Consoles and identity

Provide system-admin, tenant-admin, and tenant-operator experiences. Inventory the actual legacy TB CE version and workflows before approving a feature-parity matrix; do not infer parity from screenshots or equate operator roles with TB customer users.

Tenant-admin scope includes authorized device/asset/profile and relationship management, dashboards, telemetry/history, alarms, rules, reports, operators, and firmware when enabled. Preserve valuable existing rules through compatibility integration.

Admin and operator authentication must have separate login entry points, OIDC clients/audiences, session cookies and authorization policies. A shared identity provider is permitted; separate realms/issuers are configurable for stronger organizational separation. Operator tokens must be rejected by admin APIs. Use authorization code with PKCE, secure HttpOnly cookies for BFF sessions, CSRF protection, short session/token lifetimes, MFA for administrators, and step-up authentication for sensitive operations. Validate issuer/audience and tenant membership server-side; never trust tenant headers alone. Account disabling and grant revocation must invalidate active device access. System-admin access is separately privileged and audited.

## Tenant operators and secure device property updates

Add an Operators page: invite/activate, assign roles, scope hierarchy/device access, allowlist writable properties/actions, set access expiration, suspend/revoke, review sessions and redacted activity. Invitations are single-use and expiring. Tenant admins cannot grant beyond their authority; no self-escalation or cross-tenant delegation. Expose effective permissions before saving.

Use a server-mediated device command service rather than direct browser device credentials, raw broker access, or unrestricted network tunnels. Browser REST requests pass tenant/device/action/property authorization, schema/range validation, optimistic concurrency, rate limits, and idempotency checks. Use authenticated TLS device transport (mTLS where supported) and per-device broker ACLs. Persist desired versus reported properties with versioned command IDs, TTL, pending/acknowledged/failed/expired status, and device acknowledgments. An accepted command is not proof the device applied it. Bound offline queues; expire stale commands; reconcile reconnects; prevent replay. WebSockets expose scoped progress only. Firmware, credentials, network routing, and other high-impact settings require distinct privileges and policy-driven approval/step-up. Secrets use vault-backed operations, never generic property reads or event payloads.

## Firmware entitlement

System admins enable or revoke a tenant firmware-management entitlement independently of optional billing. Backend policy requires the tenant feature entitlement AND user permission AND device scope/compatibility. Hiding UI is insufficient. Tenant admins may delegate narrower firmware permissions to operators. Check authorization at submission and dispatch; revocation blocks queued/new dispatches and safely pauses rollout where supported, without claiming to cancel an already-running device flash. Preserve signed artifacts, provenance/SBOM, canary/rings, pause, compatibility checks, on-device verification and device-supported rollback. MQTT/TB sends notifications; HTTPS delivers binaries. Audit enablement, approval, rollout and outcomes.

## Vault support

HashiCorp Vault is the initial secrets backend; Spring Vault is a client integration for Java/Spring services, not a separate backend. Define a provider-neutral secret-reference interface with explicit provider capabilities; future AWS/Azure/GCP adapters need their own integration tests. Scope access by environment/platform, tenant, service, integration, and device. Hierarchy assignment does not implicitly grant secret access. Use workload identity, least-privilege policies, rotation/revocation and redacted audits. Do not place secret values in Git, application databases, prompts, events, logs or evidence. Browser entry uses a narrowly scoped broker/direct vault flow without persistent application storage; never expose broad vault tokens.

Vault Enterprise namespaces are optional and license-dependent. Non-Enterprise deployments require explicit policy/mount isolation or separate instances according to threat model; namespaces must not be assumed available. Provider failure must fail closed for privileged operations; telemetry continuity policy must be designed without relaxing tenant isolation.

## Delivery and acceptance

M1: vault abstraction/reference handling and configuration lifecycle. M2: tenant identities, permissions, entitlements, operator/device command domain. M3: authenticated device delivery, acknowledgment/replay/reconnect behavior. M4: separated console logins, Operators page and REST/gRPC/WebSocket integration. M5: entitled firmware management. M6: evidence, secret rotation/revocation and resilience validation. M8: identity/vault configuration in deployment plans. M9: final full legacy migration/cutover.

Each affected stage requires code, tests, docs, deployment artifacts, its complete relevant gate and full regression gate. Add negative tests for admin/operator token interchange, cross-tenant and unassigned-device access, excessive delegation, forbidden property updates, duplicate/stale commands, expired invitations, revocation during sessions/queues, firmware entitlement bypass, secret leakage and vault-policy isolation. Verify genuine device acknowledgments, configuration rollback and final migration reconciliation in representative isolated environments. PR/CI/human approval remains mandatory.

References: https://docs.spring.io/spring-vault/reference/vault/vault-client.html ; https://developer.hashicorp.com/vault/docs/enterprise/namespaces
