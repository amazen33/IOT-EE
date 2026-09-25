# ADR 0019 -- Streaming and RAG: Flink as semantic synthesizer, pgvector in production, never on the edge

Status: accepted -- encodes decisions ratified by the repository owner
on 2026-09-24. Numbered at merge per
`docs/adr/README.md`. Adds a streaming stage in front of the RAG subsystem
that ADR 0011 Decision 1 scopes to Python.

Related ADRs in this set: `0015-observability-authority.md`,
`0016-tenancy-and-identity.md`,
`0017-hexagonal-conventions.md`,
`0018-events-and-metering.md`.

## Context

ADR 0011 Decision 1 scopes Python to the RAG subsystem and standalone
adapters. Decisions 3 and 7 make Kafka + MirrorMaker the backbone and
define multi-cluster and edge profiles; the edge profile is named but
undesigned (ADR 0011 Risk 6). The AI/RAG platform sits in a separate
cluster on the far side of Kafka. No decision so far covered how
high-velocity telemetry becomes retrievable context, where that
processing runs, or which vector store is production.

## Decision

1. **Apache Flink is the semantic synthesizer.** Flink jobs subscribe to
   Kafka and aggregate raw telemetry into time-windowed insights before
   anything is written to the vector store.
2. **Placement: core or AI cluster, never K3s edge.** Flink runs only in
   the core cluster or the AI cluster. It is never deployed to a K3s
   edge profile.
3. **Data path:**

   ```
   edge MQTT → tier-1 buffer → Kafka → Flink → redaction → pgvector → RAG agent
   ```

   The **redaction** stage is mandatory and sits before any vector-store
   write: no raw PII, credentials, or device secrets reach embeddings or
   stored context (ADR 0011 Decision 4).
4. **TB CE is downstream, not the ingress owner.** TB CE sits
   downstream of the tier-1 buffer and consumes from Kafka for its own
   rule engine. This supersedes ADR 0011's description of TB CE's
   transports as owning device-facing ingestion (Decision 2 and the M3
   mapping row); ADR 0011's Status block carries a clarifying pointer.
5. **Vector store: pgvector in production; ChromaDB dev/K3s only.**
   Production uses PostgreSQL with the pgvector extension. ChromaDB is
   permitted only for local development and K3s prototyping, and no
   production profile may reference it.

## Consequences

- pgvector rows carry `tenant_id` and fall under RLS
  (`0016-tenancy-and-identity.md`); every retrieval is
  tenant-scoped. The RAG agent must never retrieve across tenants.
- pgvector keeps vector data inside the PostgreSQL backup, restore, and
  DR procedures rather than adding a separate store to recover.
- Flink jobs must preserve `X-Correlation-ID` and `traceparent` from
  input events onto derived records and spans
  (`0015-observability-authority.md`), so an insight can be
  traced back to its source events. For a windowed aggregate spanning
  many source events, how correlation is represented is C-track design
  work, not decided here.
- Flink consumes events under the broker-swap contract
  (`0018-events-and-metering.md`): at-least-once and
  per-key ordering only. Windowed outputs must be idempotent to
  re-delivery.
- Flink is JVM-based, so jobs are written in Java, consistent with ADR
  0011 Decision 1. The RAG agent itself remains in the Python scope.
- Flink jobs and the Kafka→AI-cluster mirror are broker-specific; the
  Redpanda conformance target in ADR 0018 covers the platform's
  own messaging adapters, not Flink connectors.
- The RAG path is read-only with respect to financial correctness:
  it does not feed billing (project instruction; ADR 0018).

## Open items (not decided here)

- **Blocked: TB CE's Kafka consumption mechanism.** How TB CE v4.3.1.5
  consumes platform Kafka topics into its rule engine without source
  modification is unverified (`docs/tb-ce-inventory.md` section 2.2
  found TB's integration framework to be PE/Cloud-only). Per CLAUDE.md,
  this is recorded as blocked, not passed, pending a Track A follow-up.
- **Tier-1 buffer.** The edge buffering component is not designed (ADR
  0011 Risk 6 still stands).
- **pgvector placement.** Whether pgvector lives on the OLTP cluster or
  a separate PostgreSQL instance in the AI cluster is not decided here.
- **Migration Studio is out of scope.** This ADR set does not cover the
  Migration Studio pipeline (SQLGlot/Calcite transpilation, rule-node
  and BIRT mapping). It is explicitly deferred to its own ADR, which
  must respect the contract's authorized-export, inventory,
  shadow-comparison, and approval process and ADR 0011 Risk 3.

## Alternatives rejected

- **ChromaDB in production** -- rejected by ratification: extra stateful
  store, weaker fit with PostgreSQL as the authority and with DR.
- **Flink on the edge** -- rejected by ratification: too heavy for K3s
  edge nodes.
- **Embedding raw telemetry without a synthesis stage** -- rejected:
  high-velocity raw data is not useful retrieval context.

## Cross-references

ADR 0011 Decisions 1, 3, 4, 7 and Risk 6; ADR 0012 Decisions 4, 5;
ADR 0013 (the Flink job and RAG agent generate their own classes from
`contracts/`, no shared runtime JAR);
`0018-events-and-metering.md`;
`0016-tenancy-and-identity.md`;
`0015-observability-authority.md`;
`0017-hexagonal-conventions.md`.

