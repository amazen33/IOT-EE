# Ingestion, outbox, Kafka relay, device delivery, TB shadow parity — M3

## Milestone
M3, per `docs/requirements-addendum.md`'s delivery table: "MQTT/PostgreSQL
ingestion, Kafka, TB shadow parity."

## Bounded contexts touched
telemetry-ingestion (new: `ingestion/`), alarm-command (device delivery
mechanics added to `domain_core/delivery.py`, sitting on M2's
`domain_core.commands`), migration-studio (shadow-parity comparison
framework added to `migration_studio/shadow_parity.py`).

## Verified inputs
Decisions supplied by the repository owner for this stage (see ADR 0004
for full rationale):
- Dedup/ordering: dedup by `event_id`; no cross-device ordering guarantee
  is modeled or claimed.
- MQTT topic convention: `tenant/{tenant_id}/device/{device_id}/telemetry`
  and `.../command`, QoS 1.
- Outbox schema: generic `raw_telemetry` + `outbox_event` pattern.
- TB shadow parity: build the comparison framework now; real TB-side
  parity data is explicitly out of scope and marked blocked below.

Still open per `docs/inputs.md` and explicitly **not** addressed by this
stage: real PostgreSQL physical schema and retention/time ranges,
representative legacy ThingsBoard rule exports, golden-rule parity
tolerance values calibrated against real device behavior, throughput/SLO
targets. All of these require a real database, a real Kafka cluster,
and/or an authorized ThingsBoard export that do not exist yet.

## What is implemented
- `ingestion/topics.py` — `telemetry_topic`, `command_topic`,
  `parse_topic`, `QOS_TELEMETRY`, `QOS_COMMAND`.
- `ingestion/outbox.py` — `OutboxStore` (`raw_telemetry` + `outbox_event`
  in-memory tables), `commit_telemetry_event` (validated via the existing
  `foundation.contracts.validate_event`, idempotent on repeated
  `event_id`), `OutboxCommitError`.
- `ingestion/kafka.py` — `KafkaPublisher` (provider-neutral abstract base,
  same shape as `migration_studio.vault.VaultProvider`),
  `InMemoryKafkaPublisher` (test double, with `redeliver` to simulate
  at-least-once duplicate delivery), `OutboxRelay` (`publish_pending`),
  `IdempotentConsumer` (`process_once` dedup scoped by
  `(tenant_id, event_id)`).
- `domain_core/delivery.py` — `DeviceConnectionTracker` (online/offline
  bookkeeping + history), `OfflineCommandQueue` (bounded per-device FIFO,
  `QueueFullError` on overflow, `drain_on_reconnect` with expiry-drop and
  replay-prevention via a per-device delivered-command-id watermark).
- `migration_studio/shadow_parity.py` — `compare_event` /
  `compare_events`, `ParityTolerance` (per-field numeric tolerance),
  `ParityResult` / `ParityReport` (`MATCH` / `MISMATCH` /
  `MISSING_CANONICAL` / `MISSING_SHADOW`, plus aggregate counts).
- `fixtures/shadow_parity.synthetic.json` — four synthetic canonical/shadow
  pairs (one matched, one mismatched, one missing-shadow, one
  missing-canonical) with an `expected` block, exercised end-to-end in
  `tests/test_migration_studio_shadow_parity.py::SyntheticFixtureTests`.
- `scripts/check.py` extension: M3 required-artifact checks, and the
  static no-forbidden-imports policy check extended to cover
  `ingestion/` (same technique already applied to `migration_studio/` and
  `domain_core/`).

## What is explicitly blocked (not passed, not silently skipped)
- **Real MQTT broker/client.** `ingestion/topics.py` only builds and
  parses topic strings; no socket is opened anywhere in this milestone.
- **Real PostgreSQL connection/transaction.** `OutboxStore` models the two
  tables and their atomic write in memory; no database connection exists.
- **Real Kafka cluster/client.** `KafkaPublisher` is an abstract
  interface; `InMemoryKafkaPublisher` is a test double only. A real
  adapter (e.g. `confluent-kafka`, `aiokafka`) is future integration work
  with its own separately gated integration tests.
- **Real ThingsBoard connection or live shadow capture.** `shadow_parity`
  never connects to TB; every comparison in this milestone's tests runs
  against the synthetic fixture. Real shadow data depends on the
  authorized export/inventory/approval workflow in
  `docs/migration-studio.md`, which itself has no live TB connection yet.
- **Any infrastructure provisioning.** Per `CLAUDE.md`, this remains
  offline-validation-only; no cloud resources, brokers, or databases are
  created by this stage.

These are documented here as blocked, per the contract's requirement to
record unavailable external verification as blocked rather than passed.

## Acceptance criteria (traced to the M3 gate row in docs/test-plan.md)
| Gate requirement | How it is met |
| --- | --- |
| MQTT topic/QoS convention, round-trip parse | `tests/test_ingestion_topics.py` |
| Outbox atomicity/idempotency | `tests/test_ingestion_outbox.py` (repeated `event_id` commit is a no-op; failed validation leaves no partial row) |
| At-least-once publish + consumer dedup | `tests/test_ingestion_kafka.py` (`redeliver` + `IdempotentConsumer` end-to-end test) |
| Bounded offline queue, replay prevention on reconnect | `tests/test_domain_core_delivery.py` |
| TB shadow-parity comparison framework | `tests/test_migration_studio_shadow_parity.py`, against synthetic data only (real parity data blocked, see above) |

## Security constraints observed
- Tenant authorization is preserved end-to-end: `OutboxStore.commit_telemetry_event`
  still requires an `authorized_tenant` and delegates to the existing M0
  contract check — no new, weaker validation path is introduced for the
  ingestion boundary.
- `IdempotentConsumer` dedup is scoped by `(tenant_id, event_id)`, never a
  bare `event_id`, so distinct tenants cannot collide or suppress each
  other's events.
- `OfflineCommandQueue` never redelivers a `command_id` already recorded
  as delivered to a given device, and drops expired commands on drain —
  no command can be replayed to a device or delivered past its TTL
  through this path.
- No claim of atomicity across ThingsBoard and Kafka is made anywhere in
  this module or its docs.

## Intended files
`ingestion/{__init__,topics,outbox,kafka}.py`, `domain_core/delivery.py`,
`migration_studio/shadow_parity.py`, `fixtures/shadow_parity.synthetic.json`,
`tests/test_ingestion_{topics,outbox,kafka}.py`,
`tests/test_domain_core_delivery.py`,
`tests/test_migration_studio_shadow_parity.py`,
`docs/adr/0004-m3-ingestion-outbox-delivery.md`, this file, and the
extension to `scripts/check.py`.

## Relevant tests
All `tests/test_ingestion_*.py`, `tests/test_domain_core_delivery.py`, and
`tests/test_migration_studio_shadow_parity.py`, run by
`python scripts/check.py` alongside the full cumulative regression suite.

## Non-goals (explicitly out of scope for this change)
- Any real MQTT, PostgreSQL, Kafka, or ThingsBoard connection (all
  blocked, see above).
- Cross-device event ordering guarantees.
- APISIX, SSR, WebSockets, or reports (M4).
- Firmware delivery mechanics beyond the existing
  `device.command.dispatch.firmware` permission gate (M5).
- Immutable evidence-log integration for ingestion events (M6 — the
  hash-chained log exists in `migration_studio/evidence.py` from M1 but is
  not yet wired to ingestion).
- Any infrastructure provisioning or legacy data import.
