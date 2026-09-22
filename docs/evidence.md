# Evidence, resilience & DR — M6

## Milestone
M6, per `CLAUDE.md`'s milestone list: "immutable evidence/resilience/DR."

## Bounded contexts touched
evidence (new). Reads, but does not modify, `domain_core.rbac` (one new
permission added to the catalog), `firmware.rollout`,
`migration_studio.shadow_parity`, `ingestion.outbox`, and
`reporting.telemetry_summary`.

## Verified inputs
Decisions supplied by the repository owner for this stage (see ADR 0007
for full rationale):
- Immutable evidence store: provider-neutral WORM contract, real backend
  deferred.
- Evidence scope: audit/compliance trail (RBAC decisions, firmware
  rollout/rollback events, shadow-parity comparisons), not raw telemetry
  or report snapshots.
- RPO/RTO and DR: documented targets plus a replay/reconciliation
  contract, no real DR infrastructure.
- Legal holds: an RBAC-gated flag that structurally blocks retention
  deletion.

Still open, per `docs/inputs.md`: WORM provider/region, and DR ownership
as an organizational/process assignment. Both remain explicitly
unaddressed by this stage's code.

## What is implemented
- `evidence/records.py` — `EvidenceRecord` (validated: id/tenant-id shape,
  ISO-8601 timestamps, non-empty payload), `EvidenceCategory`
  (`rbac_decision`, `firmware_rollout_event`, `shadow_parity_comparison`),
  a mechanical redaction guard (`RedactionError` on a password/token/ssn/
  card-number/secret-shaped payload key), `compute_content_hash` /
  `verify_integrity` (SHA-256 over the payload's canonical JSON).
- `evidence/worm.py` — `WormStore` (abstract, provider-neutral),
  `InMemoryWormStore` (test double): write-once `put` (`DuplicateRecordError`
  on a repeat id), `get` (integrity-checked on every read), `list_for_tenant`,
  `set_legal_hold` (metadata-only, does not touch payload or hash), and
  `expire` — the only removal path, refused by `LegalHoldActiveError`
  whenever the hold flag is set (checked before, and independent of,
  `RetentionNotElapsedError`).
- `evidence/capture.py` — pure functions mapping an already-produced
  domain decision to an `EvidenceRecord`: `capture_rbac_decision`,
  `capture_rollout_event`, `capture_shadow_parity_comparison`. None of
  these generate an id, a timestamp, or perform I/O.
- `evidence/legal_hold.py` — `set_legal_hold`, gated on the new
  `evidence.legal_hold` permission (added to
  `domain_core.rbac.PERMISSION_CATALOG`); `LegalHoldPermissionError` on an
  unauthorized caller.
- `evidence/dr.py` — `DrObjective` / `DrObjectiveCatalog` (validated,
  documented per-tenant RPO/RTO targets), `replay_telemetry_summary`
  (rebuilds a fresh `reporting.telemetry_summary.TelemetrySummaryProjector`
  from `ingestion.outbox.RawTelemetryRecord` rows), and
  `reconcile_telemetry_summary` (compares a replayed rebuild against the
  live read-model, per device, reporting any field-level drift).
- `fixtures/evidence_records.synthetic.json` — three synthetic evidence
  records, one per category, exercised end-to-end in
  `tests/test_evidence_records.py::SyntheticFixtureTests`.

## What is explicitly blocked (not passed, not silently skipped)
- **A real WORM/object-lock backend, and its provider/region.**
  `InMemoryWormStore` is an in-memory test double; no S3 Object Lock,
  Azure Immutable Blob, or on-prem WORM appliance connection exists.
- **Real cross-region failover or a completed DR drill.** Nothing in this
  milestone fails over a real system; `evidence.dr` only replays already-
  captured events through existing projection logic.
- **Real backup/restore execution.** No backup is taken or restored;
  `replay_telemetry_summary` replays in-process event objects, not a
  restored backup artifact.
- **Fault injection and load/soak testing against an approved RPO/RTO.**
  `docs/test-plan.md`'s M6 row lists these as required future gates; none
  exist yet.
- **DR ownership as an organizational assignment.** Recorded as an open
  input (`docs/inputs.md`); this milestone's code does not assign or
  enforce who owns a DR decision.

These are documented here as blocked, per the contract's requirement to
record unavailable external verification as blocked rather than passed.

## Acceptance criteria (traced to `docs/test-plan.md`'s M6 row)
| Requirement | How it is met |
| --- | --- |
| Append-only/WORM retention and privileged-deletion tests | `tests/test_evidence_worm.py` — write-once `put`, retention-gated `expire`, `evidence.legal_hold`-gated hold flag overriding expiry |
| Hash verification | `tests/test_evidence_records.py::IntegrityTests` — `compute_content_hash` / `verify_integrity`, checked on every `InMemoryWormStore.get` |
| Fault injection; load/soak | Not applicable — no real infrastructure exists this milestone (see blocked list) |
| Backup restore/DR against approved RPO/RTO | `tests/test_evidence_dr.py` — replay/reconciliation against a documented `DrObjective`; real backup/restore is blocked (see blocked list) |

## Security constraints observed
- A payload containing a raw-PII/secret-shaped key (`password`, `token`,
  `ssn`, a card-number variant, ...) cannot construct an `EvidenceRecord`
  at all — `RedactionError` is raised at construction, not caught later by
  a review step.
- `WormStore.expire` checks legal hold before, and independently of, the
  retention-elapsed check, so a held record can never be deleted through
  this path regardless of how retention is configured.
- `set_legal_hold` requires the `evidence.legal_hold` permission; there is
  no other entry point in this package that can flip a record's hold
  flag.
- `InMemoryWormStore.get` re-verifies the payload's content hash on every
  read, so a record that fails integrity is surfaced as an `IntegrityError`
  rather than silently returned.

## Intended files
`evidence/{__init__,records,worm,capture,legal_hold,dr}.py`,
`fixtures/evidence_records.synthetic.json`,
`tests/test_evidence_{records,worm,capture,legal_hold,dr}.py`,
`docs/adr/0007-m6-evidence-resilience-dr.md`, this file, the one-line
addition to `domain_core/rbac.py`'s `PERMISSION_CATALOG`, and the
extension to `scripts/check.py`.

## Relevant tests
All `tests/test_evidence_*.py`, run by `python scripts/check.py`.

## Non-goals (explicitly out of scope for this change)
- A real WORM/object-lock backend, its provider, or its region.
- Raw telemetry or reporting-snapshot evidence (only the M2/M3/M5
  audit/compliance trail is captured).
- Real cross-region failover, backup/restore execution, fault injection,
  or load/soak testing (M8 environment/infrastructure decisions).
- DR ownership as an organizational/process assignment.
- Monetization or deployment-profile concerns (M7-M8).
- Any infrastructure provisioning.
