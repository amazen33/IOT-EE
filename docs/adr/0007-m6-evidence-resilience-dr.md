# ADR 0007 — M6: immutable evidence, legal hold, and replay/reconciliation for DR

Status: accepted for planning, 2026-09-22. Implementation is milestone-gated,
same discipline as ADR 0001-0006.

## Context

M6 per `CLAUDE.md`'s milestone list is "immutable evidence/resilience/DR."
`docs/inputs.md`'s M5-M6 row listed open inputs still relevant to this
stage: RPO/RTO, evidence retention/legal holds, WORM provider/region, and
DR ownership. `docs/test-plan.md`'s M6 row further scopes the expected
gate: "Append-only/WORM retention and privileged deletion tests, hash
verification, fault injection, load/soak and backup restore/DR against
approved RPO/RTO." The repository owner supplied four decisions scoping
this stage (recorded below).

## Decisions

1. **Immutable evidence store: provider-neutral WORM contract, real
   backend deferred.** `evidence.worm.WormStore` follows the same
   abstraction shape as every prior milestone's provider interface
   (`migration_studio.vault.VaultProvider`, `ingestion.kafka.KafkaPublisher`,
   `gateway.auth.AuthProvider`, `firmware.signing.SignatureVerifier`): an
   abstract interface plus `InMemoryWormStore`, an in-memory test double.
   A real WORM backend (S3 Object Lock, Azure Immutable Blob, an on-prem
   WORM appliance) and its provider/region remain open inputs, deferred
   to M8's deployment-profile decisions.
2. **Evidence scope: the audit/compliance trail already produced by
   earlier milestones.** `evidence.capture` turns already-produced
   decisions into `evidence.records.EvidenceRecord` instances: RBAC
   authorization decisions (M2's `domain_core.rbac`), firmware
   rollout/rollback state transitions (M5's `firmware.rollout`), and
   ThingsBoard shadow-parity comparison results (M3's
   `migration_studio.shadow_parity`). Raw telemetry and reporting
   read-model snapshots are explicitly out of scope for this milestone
   (see docs/evidence.md's non-goals) -- they would multiply this
   stage's surface area without adding a new resilience/DR concern the
   audit trail doesn't already exercise.
3. **RPO/RTO and DR: documented targets plus a replay/reconciliation
   contract, no real DR infrastructure.** `evidence.dr.DrObjective`
   records per-tenant RPO/RTO as structurally validated (positive
   integer seconds), documented configuration -- not a claim that any
   real backup, cross-region failover, or DR drill meets them.
   `evidence.dr.reconcile_telemetry_summary` implements and tests the
   *procedure* a real recovery would be validated against: replaying
   `ingestion.outbox.RawTelemetryRecord` rows (M3's durable
   source-of-truth) through the same projection logic
   (`reporting.telemetry_summary.TelemetrySummaryProjector`, M4) that
   produces the live read-model, and reporting any drift. Real backup
   execution, fault injection, and load/soak testing remain blocked, per
   `docs/test-plan.md`'s M6 row, pending M8's environment/infrastructure
   decisions.
4. **Legal holds: an RBAC-gated flag that structurally blocks retention
   deletion.** `domain_core.rbac.PERMISSION_CATALOG` gains one new
   permission, `evidence.legal_hold` (extending the catalog, per its own
   comment, "as later milestones add capabilities" -- not repurposing an
   existing entry). `evidence.legal_hold.set_legal_hold` requires it.
   `evidence.worm.WormStore.expire` -- the only removal path this
   milestone defines -- refuses unconditionally while a record's
   `legal_hold` flag is set, independent of and prior to its
   retention-elapsed check, matching real WORM systems where a legal
   hold overrides even an expired retention lock.

## Consequences

- `EvidenceRecord` construction enforces two structural guarantees
  mechanically rather than by convention: a redaction guard rejects any
  payload key that looks like raw PII/secret material (password, token,
  ssn, card number, ...), and `content_sha256` is verified against the
  payload it was computed from at construction time, and re-verified by
  `InMemoryWormStore.get` on every read.
- A record under legal hold cannot be expired by any caller, however
  their retention policy is configured -- there is no code path that
  bypasses the hold, matching the "structural impossibility over policy
  note" discipline `domain_core.commands`, `gateway.routes`, and
  `firmware.rollout` already apply elsewhere in this codebase.
- The replay/reconciliation procedure gives a concrete, testable
  definition of "recovered correctly": the live read-model and events
  replayed from scratch must agree field-for-field. It says nothing about
  whether that replay would complete inside a real RTO window, or
  whether a real backup exists to replay from -- both stay blocked.
- No claim of real WORM/object-lock enforcement, real backup/restore, a
  completed DR drill, fault injection, or load/soak testing is made
  anywhere in this milestone.

## Non-goals (blocked pending real infrastructure/decisions — see `docs/evidence.md`)

- A real WORM/object-lock backend, its provider, and its region.
- Raw telemetry or reporting-snapshot evidence (only the audit/compliance
  trail from M2/M3/M5 is captured this milestone).
- Real cross-region failover, backup/restore execution, fault injection,
  or load/soak testing against an approved RPO/RTO.
- DR ownership as an organizational/process decision (recorded as an
  open input, not assigned by this milestone's code).
- Any infrastructure provisioning (still prohibited at this stage per
  `CLAUDE.md`).

