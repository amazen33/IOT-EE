# ADR 0003 — M2 domain core: tenancy, RBAC, assets, devices, commands

Status: accepted for planning, 2026-09-22. Implementation is milestone-gated,
same discipline as ADR 0001/0002.

## Context

M2 per `docs/requirements-addendum.md`'s delivery table is "tenant
identities, permissions, entitlements, operator/device command domain."
`docs/inputs.md` lists several inputs as open before M2/M3 (tenant
isolation model; device IDs/protocols; canonical units/time semantics;
tank geometry/material/formulas; dedup/ordering; throughput/SLO targets;
representative rule exports; PostgreSQL schemas; MQTT topics/QoS; golden
parity tolerances). The repository owner supplied decisions for the inputs
that actually gate M2's scope (identity/tenant + asset/tank +
device-connectivity domain shape + the command domain); the remaining
open items (MQTT topics/QoS, golden-rule parity tolerances, PostgreSQL
physical schema/time ranges, representative legacy rule exports) are M3
ingestion/shadow-parity concerns and are explicitly not addressed here.

## Decisions

1. **Tenant isolation: hybrid.** `domain_core.tenancy.choose_isolation_mode`
   assigns `SCHEMA_PER_TENANT` to every enterprise-tier tenant and to any
   standard-tier tenant whose device count passes a threshold, and
   `SHARED_RLS` otherwise. This is a pure domain decision — nothing in
   this module creates a PostgreSQL schema, applies row-level security, or
   touches a database at all. Realizing either mode against a real
   PostgreSQL instance is M3+ infrastructure work.
2. **Device identifiers: opaque, tenant-scoped UUIDs.** `domain_core.devices.DeviceId`
   carries a `tenant_id` and a UUID `value`; nothing about hierarchy,
   location, or protocol is encoded in the identifier itself. A
   human-readable `label` lives on `Device` as a separate, mutable field.
3. **Canonical units: metric + UTC everywhere.** `domain_core.units` defines
   `Percentage`, `Celsius`, and `UtcTimestamp` as the only representations
   domain code accepts, matching the wire format `foundation.contracts`
   already established at M0 (`YYYY-MM-DDTHH:MM:SSZ`). Any device-native
   unit conversion happens at the ingestion boundary (M3), never here.
4. **RBAC: fully custom roles over a fixed permission catalog.** Tenant
   admins can define arbitrary roles (`domain_core.rbac.create_role`), but
   every permission in a role must come from `PERMISSION_CATALOG` — a
   fixed, versioned set. This keeps "custom" from meaning "unchecked
   strings": authorization is still a simple set-membership test, and the
   catalog is the single place new capabilities get added as later
   milestones need them. `create_role`/`assign_role` enforce, as hard
   errors (not just documentation): no self-escalation (an assigner can
   never grant a permission they do not themselves hold) and no
   cross-tenant delegation, except for a system-admin principal, which is
   a distinct `PrincipalKind`, never a tenant-scoped role.
5. **Operator/device command domain.** `domain_core.commands` implements
   desired-vs-reported command state (`CommandRequest`), an operator's
   allowlisted device scope and writable-property list
   (`OperatorDeviceScope`), and `CommandStore` for dispatch/acknowledge/
   fail with TTL-based expiry and idempotent dispatch keyed by
   `(device, property, idempotency_key)`. `authorize_command` is a
   standalone function so every failure mode (cross-tenant, unassigned
   device, forbidden property, missing permission, expired scope,
   mismatched scope owner, system-admin attempting direct dispatch) is
   independently testable. This module has no transport: it does not talk
   to MQTT, a broker, or a device. That is M3's "authenticated device
   delivery, acknowledgment/replay/reconnect behavior."

## Consequences

- M3's ingestion/command-delivery layer can be built directly on
  `DeviceId`, `Tenant`, `Principal`, and `CommandRequest` without
  redefining tenancy, identity, or command shape.
- The hybrid isolation decision is visible and testable now, before any
  real PostgreSQL schema exists, so M3's migration/provisioning work has
  an unambiguous target to implement against instead of inventing the
  policy at integration time.
- A future PR that tries to smuggle a raw permission string not in
  `PERMISSION_CATALOG`, or a role grant that escalates privilege, fails at
  construction time (`ValueError`/`SelfEscalationError`), not at review
  time.

## Non-goals

- Any real PostgreSQL schema creation, migration, or row-level security
  policy (M3+ infrastructure).
- MQTT topic/QoS design, transactional outbox, or any transport (M3).
- Golden-rule shadow parity, legacy rule export inventory (M3/M9).
- Tank geometry/volume formulas (still an open input per `docs/inputs.md`;
  `Tank.geometry` is a validated-shape placeholder only).
- Reports, dashboards, or any UI (M4).

