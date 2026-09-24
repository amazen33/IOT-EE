# ADR 0004 — M3: MQTT topics, transactional outbox, Kafka relay, device delivery, TB shadow parity

Status: accepted for planning, 2026-09-22. Implementation is milestone-gated,
same discipline as ADR 0001/0002/0003.

## Context

M3 per `docs/requirements-addendum.md`'s delivery table is "MQTT/PostgreSQL
ingestion, Kafka, TB shadow parity." `docs/inputs.md` listed several open
inputs gating this milestone: dedup/ordering semantics, MQTT topic
convention and QoS, outbox physical schema, and how to approach
ThingsBoard shadow-parity given no authorized TB export/live instance is
available yet. The repository owner supplied decisions for all four
(recorded below); nothing in this ADR was inferred without an explicit
answer.

## Decisions

1. **Dedup/ordering: dedup by `event_id`, no cross-device ordering
   guarantee.** `ingestion.kafka.IdempotentConsumer` scopes dedup by
   `(tenant_id, event_id)`, matching the shape of the M0 telemetry
   contract's own identifiers. Per-device ordering is explicitly **not**
   modeled here — a real deployment would get that from Kafka partitioning
   by `device_id`, which is infrastructure this milestone does not stand
   up. Tests exercise duplicate delivery (`InMemoryKafkaPublisher.redeliver`)
   but never assert an ordering guarantee this module doesn't provide.
2. **MQTT topic convention: `tenant/{tenant_id}/device/{device_id}/telemetry`
   and `.../command`, QoS 1.** `ingestion.topics` implements and validates
   this shape (`telemetry_topic`, `command_topic`, `parse_topic`), with the
   same identifier character class the M0 contract already enforces
   (`[a-zA-Z0-9_-]{1,128}`). QoS 1 (at-least-once) is a deliberate match to
   the outbox/consumer design below: the system is built to tolerate
   duplicate delivery by dedup, not to lean on a stronger QoS or broker
   exactly-once semantics it does not implement.
3. **Outbox schema: generic `raw_telemetry` + `outbox_event` pattern.**
   `ingestion.outbox.OutboxStore` models both tables and the transaction
   that must write to them atomically. `commit_telemetry_event` validates
   against the existing `foundation.contracts.validate_event` (never
   re-implementing that contract) and is itself idempotent on a repeated
   `event_id` — a publisher/producer retry at the ingestion boundary is a
   no-op, not a re-validation or overwrite. `ingestion.kafka.OutboxRelay`
   publishes pending rows and marks them sent; it is safe to call
   repeatedly because an already-published row is never re-selected.
4. **TB shadow parity: build the comparison framework now; real parity
   data is blocked.** `migration_studio.shadow_parity` compares a
   canonical (Kafka/PostgreSQL-side) event against a captured TB shadow
   reading — per-field, with configurable numeric tolerance
   (`ParityTolerance`) — and reports `MATCH` / `MISMATCH` /
   `MISSING_CANONICAL` / `MISSING_SHADOW` per event plus an aggregate
   `ParityReport`. It contains **no** ThingsBoard client and never
   connects to a live TB instance: real shadow capture depends on the
   authorized export/inventory/approval workflow `migration_studio.sources`
   already models, which itself has no real TB connection yet. Every test
   in this milestone runs the framework against synthetic
   `fixtures/shadow_parity.synthetic.json` pairs only.
5. **Device delivery: connection tracking + bounded offline queue with
   replay prevention.** `domain_core.delivery.DeviceConnectionTracker` is
   pure online/offline bookkeeping (no transport). `OfflineCommandQueue`
   enforces a fixed per-device capacity (`QueueFullError` on overflow,
   deliberately not a silent drop) and, on `drain_on_reconnect`, drops
   expired `CommandRequest`s and skips any `command_id` already recorded
   as delivered to that device — the replay-prevention watermark the
   addendum requires for reconnect behavior. This sits directly on M2's
   `domain_core.commands.CommandRequest`; it does not redefine command
   shape or expiry logic.

## Consequences

- The outbox/relay/consumer chain gives M3 an at-least-once,
  duplicate-tolerant pipeline shape that M4's reporting/read-model
  projections and M6's evidence log can build on without redesigning
  delivery semantics.
- The shadow-parity framework is exercised and correct against synthetic
  data now, so the moment an authorized TB export/shadow capture exists,
  wiring it in is a data-source change, not a from-scratch comparison
  design.
- No claim of atomicity across ThingsBoard and Kafka is made anywhere in
  this milestone, or implied by the shadow-parity framework: it reports
  observed differences between two already-captured readings, nothing
  more.

## Non-goals (blocked pending real infrastructure — see `docs/ingestion.md`)

- A real MQTT broker connection or client.
- A real PostgreSQL connection, schema migration, or transaction.
- A real Kafka client, cluster, topic, or partition assignment.
- A real ThingsBoard connection, authorized rule export, or live shadow
  capture.
- Any infrastructure provisioning (still prohibited at this stage per
  `CLAUDE.md`; M3 remains offline-validation-only, same as M0/M1/M2).

