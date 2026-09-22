# ADR 0005 — M4: gateway auth/route contract and reporting read-models

Status: accepted for planning, 2026-09-22. Implementation is milestone-gated,
same discipline as ADR 0001-0004.

## Context

M4 per `docs/requirements-addendum.md`'s delivery table and
`docs/test-plan.md`'s gate row is "APISIX auth/rate/version tests, SSR/BFF
end-to-end, WebSocket subscription authorization/reconnect, accessibility,
reconciled certified reports and publication approval." `docs/inputs.md`
listed open inputs gating this milestone: UI priorities, identity
provider, hierarchy/location rules, report definitions and sanitized
expected results, timezone/refill-consumption semantics, accessible
design requirements, report approvers. The repository owner narrowed this
milestone's actual deliverable and supplied four decisions gating scope
(recorded below as 1-4); decision 5 (dependency policy) followed from a
CI failure discovered after implementation, not a pre-scoped input; the full SSR/WebSocket/accessibility surface named in the gate
row is **not** built this slice — see Non-goals.

## Decisions

1. **Scope: reporting read-models + APISIX/auth contract first.** Rather
   than a thin pass across all four M4 areas, this slice builds the
   reporting CQRS read-model and the gateway auth/route contract
   end-to-end, and treats SSR and WebSocket delivery as contract-only
   (route shape defined and validated, no renderer or server built). This
   gives the milestone one narrow vertical slice that is fully real
   (tested, no unverified assumption) instead of four shallow ones.
2. **Identity provider: provider-neutral abstraction, real IdP deferred.**
   `gateway.auth.AuthProvider` follows the same shape as
   `migration_studio.vault.VaultProvider` and `ingestion.kafka.KafkaPublisher`:
   an abstract token-authentication interface plus `InMemoryAuthProvider`,
   a test double only. It resolves a token into a
   `domain_core.rbac.Principal`-carrying session — RBAC itself is not
   re-implemented, only wrapped with issued/expiry semantics at the
   gateway boundary. A real OIDC/SAML adapter is future integration work.
3. **First report definition: tank level/telemetry summary per tenant.**
   `reporting.telemetry_summary.TelemetrySummaryProjector` projects M3's
   `ingestion.outbox.RawTelemetryRecord` rows (chosen over
   `ingestion.kafka.DeliveredEvent` because `OutboxEvent.payload` is
   deliberately slim and carries neither `device_id` nor `occurred_at` —
   see the module's own docstring) into a per-tenant, per-device summary:
   latest/min/max level, latest temperature/battery, and a
   consumption-rate calculation. Projection is idempotent per
   `(tenant_id, event_id)`, matching M3's at-least-once delivery model.
   **Read-model storage: SQLite, real PostgreSQL blocked.** The projector
   is backed by stdlib `sqlite3` (in-memory by default) with a real
   `CREATE TABLE` schema and `ON CONFLICT ... DO UPDATE` upserts, rather
   than a plain in-memory dict — this is deliberately the one M4 module
   where persistence is modeled at all, since a materialized read-model's
   whole job is persistence, and a real schema catches shape mistakes now
   instead of at real-PostgreSQL integration time. `domain_core`,
   `gateway`, and `migration_studio` all remain pure logic, unchanged.
4. **Timezone/refill semantics: UTC storage and computation unchanged;
   tenant-local display only.** `reporting.timezone.to_tenant_local`
   formats an already-canonical UTC instant in a tenant-configured IANA
   timezone for display. Every computation — the consumption-rate delta
   in `telemetry_summary`, the report's content-hash fingerprint in
   `publication` — stays in UTC; no calendar-aware billing period or
   refill-cycle semantics are introduced.

5. **First external dependency: `tzdata`, pure-data only, pinned.**
   `reporting.timezone` uses stdlib `zoneinfo`, which has no bundled
   timezone database on Windows and depends on the platform providing
   one — GitHub Actions' `windows-latest` runner has none, so CI failed
   resolving even `'UTC'`. `requirements.txt` pins `tzdata>=2024.1`, the
   CPython-team-maintained IANA database package with no importable
   code of its own, installed in CI before the gate runs. This is the
   project's first dependency after M0-M4's fully stdlib-only code, and
   sets the precedent for `docs/inputs.md`'s still-open
   "dependency/security tooling policy" input: a new dependency must be
   pure-data or otherwise zero-risk, pinned to a minimum version, and
   added via reviewed PR — never unpinned or pulled in transitively
   without review. Broader tooling (automated scanning, license policy,
   upgrade cadence) remains an open input.

"Reconciled certified reports and publication approval"
(`reporting.publication.publish_report`) is modeled as a
`reports.publish`-gated action that fingerprints the published rows with
a SHA-256 content hash — not a full reconciliation workflow, and not
wired into `migration_studio.evidence.EvidenceLog`'s hash-chained,
tamper-evident log (that integration is real future work, deferred to M6
per CLAUDE.md's own milestone list, "M6 immutable evidence/resilience/DR").

## Consequences

- `gateway.routes.RouteDefinition` mechanically requires a literal
  `{tenant_id}` segment in every route's path template, so a tenant-scoped
  route cannot be defined without tenant scoping baked into its own
  contract — the same "structural impossibility over policy note"
  discipline `domain_core.commands` already applies to cross-tenant
  command dispatch.
- `deployment/m4-gateway-routes.json` is a reviewable, inert config
  artifact (same role as `deployment/m0-plan.json`), asserted reproducible
  from `gateway.routes.ROUTE_CATALOG` by the test suite, so it cannot
  drift from the code that generates it.
- The SSR dashboard route and WebSocket subscription route exist as
  validated contracts (`implemented=False`) now, so the moment a real SSR
  renderer or WebSocket server is built, its route shape and
  auth/permission requirement are already agreed and tested — the
  remaining work is the backend, not the contract.
- No accessibility audit, real APISIX deployment, or real IdP integration
  is claimed as done by this change.

## Non-goals (blocked pending real infrastructure/decisions — see `docs/gateway.md` and `docs/reporting.md`)

- A real APISIX instance, Admin API call, or applied route (the generated
  config is a contract artifact, not a deployment).
- A real identity provider connection (OIDC discovery, JWKS verification,
  SAML assertion validation).
- SSR page rendering or a BFF layer.
- A real WebSocket server, subscription lifecycle, or reconnect handling.
- Accessibility (WCAG) audit or design requirements — no UI exists yet to
  audit.
- Wiring report publication into the hash-chained evidence log (M6).
- Any infrastructure provisioning (still prohibited at this stage per
  `CLAUDE.md`).
