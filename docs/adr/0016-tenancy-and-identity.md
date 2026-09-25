# ADR XXXX (proposed) -- Tenancy and identity: pooled tenant_id + Postgres RLS; IAM-agnostic OIDC; 2-tier RBAC

Status: proposed -- encodes decisions ratified by the repository owner
on 2026-09-24. Unnumbered draft per `docs/adr/README.md`; the number is
assigned at merge. Refines ADR 0011 Decision 5 (multi-tenancy and
security model); supersedes the "multi-tenant by schema" wording in the
project brief, which no merged ADR had adopted.

Related drafts in this set: `0015-observability-authority.md`,
`0017-hexagonal-conventions.md`,
`0018-events-and-metering.md`,
`0019-streaming-and-rag.md`.

## Context

ThingsBoard CE, which ADR 0011 Decision 2 makes the compatibility core,
isolates tenants by a `tenant_id` column in shared tables. Every Python
spec ADR from 0003 onward (domain core, ingestion, gateway, monetization,
deployment studio, WORM adapter) already models `tenant_id` as a field.
The project brief separately said "multi-tenant by schema". The two had
never been reconciled.

ADR 0011 Decision 5 names RBAC + ABAC + mTLS/JWT/SSO, with JWT/SSO
validated at the APISIX edge and "tenant-admin" / "sys-admin" roles. It
names no identity provider. The current `services/identity` walking
skeleton has a framework-free `rbac/` package and an ABAC hook that is
a stub (`AbacContext.alwaysPermit()`).

## Decision

1. **Pooled tenancy.** All tenant-owned rows in every service's
   PostgreSQL schema carry a non-null `tenant_id`. Isolation is enforced
   first in application code (the ABAC tenant check) and second by
   **PostgreSQL Row-Level Security**.
2. **RLS mechanism: `SET LOCAL app.tenant_id`.** Every transaction that
   touches tenant-owned tables sets `app.tenant_id` with `SET LOCAL`
   before its first statement; each tenant-owned table has RLS enabled
   and a policy of the shape

   ```sql
   ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;
   ALTER TABLE <t> FORCE ROW LEVEL SECURITY;
   CREATE POLICY tenant_isolation ON <t>
     USING (tenant_id = current_setting('app.tenant_id')::uuid)
     WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);
   ```

   (illustrative; column type follows each service's own schema).
   `SET LOCAL` is required, not `SET`: its scope ends with the
   transaction, so a pooled connection cannot carry one tenant's setting
   into another tenant's request.
3. **IAM-agnostic OIDC.** Services depend on standard OAuth2/OIDC and
   JWT only. Keycloak is the initial provider implementation and nothing
   more. Token claims are mapped to an internal, framework-free
   `Principal` (subject, tenant, tier, role) through an **outbound
   port**; which claim names hold tenant and role is provider
   configuration, not code.
4. **The service is the trust boundary.** Each service re-verifies the
   JWT itself (signature, issuer, audience, expiry) and maps its claims
   to its own `Principal` locally. Identity headers set by APISIX after
   edge validation are advisory, not authoritative: a service never
   grants access on the strength of an APISIX header alone.
5. **Vendor ban.** An ArchUnit rule bans `org.keycloak..` everywhere
   except the identity adapter package that implements that port. Per
   ADR 0013, each service declares this rule itself using the
   `architecture/` factories; there is no shared security JAR.
6. **2-tier RBAC.**
   - Tier 1 (Product): **Product Admin** (`PRODUCT_ADMIN`), **Product
     Operator** -- platform-wide scope. ADR 0011 Decision 5's
     "sys-admin" is `PRODUCT_ADMIN`; there is no separate sys-admin
     role.
   - Tier 2 (Tenant): **Tenant Admin**, **Tenant Operator** -- scoped to
     one `tenant_id`.
   ABAC decides tenant scope: a Tier 2 principal may act only where the
   resource's `tenant_id` equals the principal's tenant.

## Consequences

- The service's database role must not own the tables and must not have
  `BYPASSRLS` or superuser. Owners bypass RLS unless `FORCE ROW LEVEL
  SECURITY` is set, which is why the policy shape above includes it.
  Migrations run under a separate owner role.
- A missing `app.tenant_id` must fail closed: `current_setting` without
  the missing-ok flag raises, which is the intended behaviour.
- Cross-tenant operations by Tier 1 roles need an explicit, audited path
  (for example, a dedicated role or per-statement setting). Its exact
  mechanism is C3 design work, not decided here.
- `AbacContext.alwaysPermit()` cannot survive into any tenant-facing
  endpoint. Replacing it is a precondition, not a follow-up.
- Tests must use synthetic JWTs signed by a test key. No real IdP, no
  real tenant identifiers (constitutional rule, ADR 0011 Decision 4).
- `pgvector` data (`0019-streaming-and-rag.md`) and outbox or
  dedupe tables (`0018-events-and-metering.md`) carry
  `tenant_id` and fall under the same RLS rule.

## Resolved conflicts with ADR 0011 (repository owner, 2026-09-24)

- **Where tokens are validated.** ADR 0011 Decision 5 and its M4
  mapping row said Spring services "trust APISIX-validated identity
  headers". Resolved by Decision 4 above: services re-verify the JWT;
  APISIX headers are advisory; the trust boundary is the service. ADR
  0011's Status block points here.
- **Role names.** ADR 0011's "sys-admin" is the Tier 1 `PRODUCT_ADMIN`
  role (Decision 6 above); ADR 0011 Decision 4 and 5's references now
  say so.

## Notes

- **Standard claims.** Standard JWT claims have no tenant or role claim.
  "Standard claims only" in practice means standard claims plus
  provider-configured custom claim names mapped by configuration.

## Alternatives rejected

- **Schema-per-tenant** (the project brief's wording) -- rejected by
  ratification: diverges from TB CE's model and multiplies migrations
  per tenant.
- **Application-only isolation without RLS** -- rejected: no second
  line of defence.
- **Keycloak adapters or SDK in services** -- rejected: provider lock-in.

## Cross-references

ADR 0011 Decisions 4, 5 and the M2/M4 mapping rows; ADR 0012 Decision 6
(walking skeleton's RBAC); ADR 0013 Decisions 1, 5, 6 (per-service RBAC,
ArchUnit enforcement, accepted duplication);
`0017-hexagonal-conventions.md` (where the port and adapter
live); `0015-observability-authority.md` (the principal is
never placed in baggage).

