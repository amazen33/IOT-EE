# ADR XXXX (proposed) -- Events and metering: transactional outbox, idempotent consumers, usage-event v1, broker-swap contract, payment port

Status: proposed -- encodes decisions ratified by the repository owner
on 2026-09-24. Unnumbered draft per `docs/adr/README.md`; the number is
assigned at merge. Implements ADR 0011 Decision 6 (pay-as-you-go
metering, optional monetization) in the Java platform; scopes, without
overriding, ADR 0011's M3 mapping row (see "Conflicts with ADR 0011").

Related drafts in this set: `XXXX-proposed-observability-authority.md`,
`XXXX-proposed-tenancy-and-identity.md`,
`XXXX-proposed-hexagonal-conventions.md`,
`XXXX-proposed-streaming-and-rag.md`.

## Context

The contract requires transactional outbox and idempotent consumers,
and forbids any claim of atomicity across ThingsBoard and Kafka. ADR
0011 Decision 3 extends that to "no distributed atomic-transaction
claim across Kafka and any other system". ADR 0011 Decision 6 makes
metering cross-cutting and always-on, and monetization an optional,
default-off pure Kafka consumer of finalized `UsageMetered` events,
carrying forward M7's prohibition on any `is_feature_entitled`-shaped
function in the billing path. ADR 0012 Decision 4 fixed Protobuf as
the platform-wide envelope format; `contracts/events/v1/envelope.proto`
already carries `tenant_id` and `idempotency_key`. `CLAUDE.md` names
Kafka as replaceable (Redpanda) and payments (Stripe) as behind a port.

## Decision

1. **Transactional outbox + relay.** A service that publishes an event
   writes the event row to its own `outbox` table in the **same database
   transaction** as the state change. A separate relay publishes outbox
   rows to the broker and marks them sent. Publishing is at-least-once;
   nothing claims the state change and the broker write are atomic.
2. **Idempotent consumers with dedupe tables.** Each consumer records
   `(consumer_name, event_id)` in its own dedupe table, with a primary
   key on that pair, in the **same transaction** as the consumer's
   effect. A duplicate delivery hits the key and the effect is skipped.
3. **`usage-event v1` schema in `contracts/`.** Defined as a Protobuf
   payload carried inside the existing v1 envelope (ADR 0012 Decision
   4), with the envelope's `idempotency_key` as the metering dedupe key.
   Versioning is additive by default and compatibility-tested (ADR 0011
   Decision 4).
4. **Broker-swap contract.** Code outside the messaging adapter may
   rely only on:
   - **at-least-once** delivery;
   - **ordering per partition key** only, never global ordering; each
     event type declares its key in `contracts/`;
   - **no cross-system transactions** (no XA/2PC, no Kafka
     transactions spanning the database).
   Anything stronger a broker offers is not part of the contract.
5. **CI conformance: Kafka and Redpanda.** The messaging adapter's
   integration tests run against both Kafka and Redpanda in CI. A
   broker is a supported swap target only if it passes the same suite.
6. **Payment port.** Financial transactions go through a
   `PaymentGateway` outbound port. Stripe is the first adapter behind
   it. There are **zero `com.stripe..` imports** outside that adapter
   package, enforced by the per-adapter ban in
   `XXXX-proposed-hexagonal-conventions.md`. Monetization stays
   default-off and a consumer only, per ADR 0011 Decision 6.

## Consequences

- Outbox, relay, and dedupe tables carry `tenant_id` and fall under RLS
  (`XXXX-proposed-tenancy-and-identity.md`). The relay therefore needs
  its own audited cross-tenant read path; its mechanism is C4 design.
- Each event carries `X-Correlation-ID` and `traceparent` as Kafka
  headers (`XXXX-proposed-observability-authority.md`). The relay must
  copy them from the outbox row, not mint new ones.
- Billing correctness depends on dedupe by `idempotency_key`, not on
  broker guarantees. A billed unit exists only once per key, however
  many deliveries occur.
- Payment-provider egress must strip OTel baggage (observability draft).
- Nothing in this ADR puts AI or the RAG path into financial
  correctness (project instruction); `XXXX-proposed-streaming-and-rag.md`
  consumes usage events read-only if at all.

## Conflicts with ADR 0011

- **ADR 0011 M3 mapping row** said: do not rebuild "the transport-layer
  transactional outbox, or a custom Kafka relay". Resolved by the
  repository owner (2026-09-24): that row covers **device ingestion
  only**. The transactional-outbox rule in Decision 1 applies to every
  other publishing path. ADR 0011's Status block carries a clarifying
  pointer.
- **Feature toggles vs. ADR 0011 Decision 6.** ADR 0011 keeps
  entitlement/feature-gating out of the billing path. This ADR adds no
  entitlement function; any feature-toggle mechanism for metered
  operations needs its own decision that respects Decision 6.

## Alternatives rejected

- **Dual write** (commit to the database, then publish to the broker in
  application code) -- rejected: loses events on a crash between the two.
- **Distributed transactions (XA/2PC) or Kafka transactions spanning the
  database** -- rejected by the contract and ADR 0011 Decision 3.
- **Kafka-only CI** -- rejected: the swap claim would be untested.
- **Stripe SDK in core** -- rejected: payment-provider lock-in.

## Cross-references

ADR 0011 Decisions 3, 4, 6 and the M3/M7 mapping rows; ADR 0012
Decision 4 (Protobuf envelope) and Decision 5 (correlation header);
ADR 0013 Decisions 2, 9 (contracts shared, generated per service);
`XXXX-proposed-hexagonal-conventions.md`;
`XXXX-proposed-tenancy-and-identity.md`;
`XXXX-proposed-observability-authority.md`;
`XXXX-proposed-streaming-and-rag.md`.
