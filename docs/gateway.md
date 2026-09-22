# Gateway — M4

## Milestone
M4, per `docs/requirements-addendum.md`'s delivery table and
`docs/test-plan.md`'s gate row: "APISIX auth/rate/version tests, SSR/BFF
end-to-end, WebSocket subscription authorization/reconnect, accessibility,
reconciled certified reports and publication approval." This document
covers the gateway half (auth contract + route/policy contract); see
`docs/reporting.md` for the reporting-read-model half.

## Bounded contexts touched
gateway (new), identity-tenant (auth wraps M2's `domain_core.rbac.Principal`,
does not redefine it).

## Verified inputs
Decisions supplied by the repository owner for this stage (see ADR 0005
for full rationale):
- Scope: build the reporting read-model and the APISIX/auth contract
  first; SSR and WebSocket delivery are contract-only this slice.
- Identity provider: provider-neutral abstraction now, real IdP deferred.

Still open per `docs/inputs.md` and explicitly **not** addressed by this
stage: which real identity provider to integrate, UI priorities, hierarchy/
location display rules, accessible design requirements (no UI exists to
audit yet).

## What is implemented
- `gateway/auth.py` — `AuthProvider` (abstract, provider-neutral),
  `InMemoryAuthProvider` (test double), `AuthenticatedSession`
  (issued/expiry wrapping a `domain_core.rbac.Principal`),
  `require_tenant_match`, `require_permission`, and
  `AuthenticationError` / `SessionExpiredError` / `TenantMismatchError` /
  `PermissionDeniedError`.
- `gateway/routes.py` — `RouteDefinition` (validated: absolute path,
  literal `{tenant_id}` segment required, known protocol, a real
  permission from `domain_core.rbac.PERMISSION_CATALOG` when
  `auth_required`), `ROUTE_CATALOG` (the M4 route set),
  `build_gateway_route_config` (renders an inert, APISIX-shaped
  route/policy document — not a literal Admin API payload, and never
  applied to a real gateway).
- `deployment/m4-gateway-routes.json` — the committed, reviewable route
  config, asserted reproducible from `ROUTE_CATALOG` by
  `tests/test_gateway_routes.py`.

## What is explicitly blocked (not passed, not silently skipped)
- **A real APISIX instance or Admin API call.** `build_gateway_route_config`
  produces data only; nothing in this module opens a socket or calls an
  API.
- **A real identity provider.** `InMemoryAuthProvider` is a test double;
  no OIDC discovery, JWKS verification, or SAML assertion validation
  exists anywhere in this milestone.
- **SSR rendering.** `ssr-tenant-dashboard`'s route contract exists
  (`implemented=False`); no renderer or BFF layer is built.
- **A real WebSocket server.** `ws-tenant-telemetry-subscription`'s route
  contract exists (`implemented=False`); no server, subscription
  lifecycle, or reconnect handling is built.
- **Rate limiting and versioning enforcement.** The route config carries a
  `rate-limit` plugin slot with a target requests-per-minute value; no
  real rate limiter enforces it, and no API versioning scheme is defined
  yet.
- **Accessibility.** No UI exists yet to audit against any accessibility
  requirement.

These are documented here as blocked, per the contract's requirement to
record unavailable external verification as blocked rather than passed.

## Acceptance criteria (traced to the M4 gate row in docs/test-plan.md)
| Gate requirement | How it is met |
| --- | --- |
| APISIX auth tests | `tests/test_gateway_auth.py` — authentication, expiry, tenant-match, permission checks |
| APISIX route/version contract | `tests/test_gateway_routes.py` — route validation + reproducible config artifact |
| Rate limiting | Modeled as a `rate-limit` plugin slot on each route; not yet enforced by a real limiter (see blocked list) |
| SSR/BFF end-to-end | Not built — contract-only route exists, see blocked list |
| WebSocket subscription authorization/reconnect | Not built — contract-only route exists (`device.view` permission named); reconnect behavior overlaps `domain_core.delivery` (M3) for the device side, but no WS server exists to authorize into |
| Accessibility | Not applicable yet — no UI |

## Security constraints observed
- Every tenant-scoped route's path template mechanically requires a
  literal `{tenant_id}` segment (`RouteDefinition.__post_init__` raises
  `InvalidRouteDefinition` otherwise) — tenant scoping cannot be omitted
  from a route's own contract.
- Every `auth_required` route must name a `required_permission` that is a
  real member of `domain_core.rbac.PERMISSION_CATALOG` — no route can
  gate on a permission string that does not exist.
- `require_tenant_match` rejects cross-tenant access for every principal
  kind except system-admin, which is a structurally distinct principal
  kind (per M2's `domain_core.rbac`), never a tenant-scoped role.
- A session past its `expires_at` is rejected (`SessionExpiredError`)
  rather than silently treated as valid.

## Intended files
`gateway/{__init__,auth,routes}.py`, `deployment/m4-gateway-routes.json`,
`tests/test_gateway_{auth,routes}.py`, `docs/adr/0005-m4-gateway-reporting.md`,
this file, and the extension to `scripts/check.py`.

## Relevant tests
`tests/test_gateway_auth.py`, `tests/test_gateway_routes.py`, run by
`python scripts/check.py`.

## Non-goals (explicitly out of scope for this change)
- Any real APISIX, IdP, SSR, or WebSocket backend (all blocked, see above).
- Accessibility audit or design requirements.
- Firmware, evidence-log, or monetization integration (M5-M7).
- Any infrastructure provisioning.
