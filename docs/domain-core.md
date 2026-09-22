# Domain core — M2

## Milestone
M2 (domain core), per `docs/requirements-addendum.md`'s delivery table:
"tenant identities, permissions, entitlements, operator/device command
domain."

## Bounded contexts touched
identity-tenant, asset-tank, device-connectivity, alarm-command (command
domain only — alarms themselves are not implemented yet).

## Verified inputs
Decisions supplied by the repository owner for this stage:
- Tenant isolation: hybrid — schema-per-tenant for enterprise-tier tenants
  (and any tenant that outgrows the shared-schema threshold), shared
  schema + row-level security otherwise.
- Device identifiers: opaque, tenant-scoped UUIDs; human labels are
  separate and mutable.
- Canonical units/time: metric units, UTC timestamps everywhere in the
  domain model (matches the M0 telemetry contract's wire format).
- Entitlements/roles: fully custom, tenant-defined RBAC roles, built only
  from a fixed permission catalog.

Still open per `docs/inputs.md` and explicitly **not** addressed by this
stage: MQTT topics/QoS, dedup/ordering rules, PostgreSQL physical schema
and time ranges, representative legacy rule exports, golden-rule parity
tolerances (all M3); identity provider selection, report definitions,
timezone/refill semantics (M4); firmware signing/trust, RPO/RTO (M5-M6);
monetization scope, deployment profiles (M7-M8).

## What is implemented
- `domain_core/tenancy.py` — `Tenant`, `TenantSizeTier`, `IsolationMode`,
  `choose_isolation_mode`, `derive_schema_name`.
- `domain_core/units.py` — `Percentage`, `Celsius`, `UtcTimestamp`.
- `domain_core/rbac.py` — `PERMISSION_CATALOG`, `Principal`,
  `PrincipalKind`, `Role`, `create_role`, `assign_role`, with
  `SelfEscalationError` / `CrossTenantDelegationError`.
- `domain_core/assets.py` — `Region`, `Site`, `Tank`, `AssetHierarchy`
  (tenant-scoped relations, `CrossTenantRelationError`).
- `domain_core/devices.py` — `DeviceId`, `Device`, `DeviceRegistry`.
- `domain_core/commands.py` — `OperatorDeviceScope`, `CommandRequest`,
  `CommandStore`, `authorize_command`.
- `fixtures/domain_core.synthetic.json` — synthetic tenants/hierarchy/role
  wired together end-to-end in `tests/test_domain_core_fixture.py`.
- `scripts/check.py` extension: M2 required-artifact checks and a static
  policy check rejecting network/process-capable imports anywhere under
  `domain_core/` (same technique as M1's `migration_studio/` check).

## Acceptance criteria (traced to the M2 gate row in docs/test-plan.md)
| Gate requirement | How it is met |
| --- | --- |
| Tenant boundary/negative authorization tests | `CrossTenantRelationError`, `CrossTenantDelegationError`, and command-domain cross-tenant/unassigned-device tests |
| Device/asset relations | `AssetHierarchy` (region → site → tank), `Device` linked to a `tank_id` |
| Domain invariants | Frozen dataclasses validate every field at construction; invalid states cannot be built |
| Unit/formula reference cases | `domain_core.units` canonical value objects; `Tank.geometry` is a validated placeholder for the still-open formula input |
| Schema compatibility | `derive_schema_name` + `IsolationMode` model the target PostgreSQL schema shape without touching a database |

## Security constraints observed
- No self-escalation or cross-tenant delegation is possible through the
  public API (`create_role`/`assign_role`) — both are hard errors, covered
  by negative tests.
- System-admin is a distinct principal kind that can never carry a
  `tenant_id`, and cannot dispatch a device command directly (mirrors the
  addendum's "operator tokens must be rejected by admin APIs" by making
  admin and operator action paths structurally different, not just
  policy-separated).
- Commands are authorized against an explicit allowlist of devices and
  writable properties per operator, with TTL-bound expiry and idempotent
  dispatch — no property write is possible outside that allowlist.

## Intended files
`domain_core/{__init__,tenancy,units,rbac,assets,devices,commands}.py`,
`fixtures/domain_core.synthetic.json`,
`tests/test_domain_core_{tenancy,rbac,assets,devices,commands,fixture}.py`,
`docs/adr/0003-m2-domain-core.md`, this file, and the extension to
`scripts/check.py`.

## Relevant tests
All `tests/test_domain_core_*.py` files, run by `python scripts/check.py`.

## Non-goals (explicitly out of scope for this change)
- Any real PostgreSQL schema/migration/RLS policy.
- MQTT/transport, transactional outbox (M3).
- Golden-rule shadow parity, legacy rule/report inventory (M3/M9).
- Tank volume/geometry formulas (open input).
- Alarms (only the command domain is in scope this stage).
- Any UI, report, or dashboard (M4).
- Firmware entitlement enforcement beyond the `device.command.dispatch.firmware`
  permission gate already modeled in `domain_core.commands` (full firmware
  lifecycle is M5).
