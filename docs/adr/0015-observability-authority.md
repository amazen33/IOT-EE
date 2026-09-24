# ADR XXXX (proposed) -- Observability authority: LGTM is the enterprise trace/log plane; dual-ID propagation

Status: proposed -- encodes a decision ratified by the repository owner
on 2026-09-24. Unnumbered draft per `docs/adr/README.md`; the number is
assigned at merge. On merge, this ADR supersedes the "Observability"
bullet of ADR 0011 Decision 3 insofar as that bullet
calls LGTM "complementary to, not a replacement for" TB CE's built-in
monitoring.

Related drafts in this set: `0016-tenancy-and-identity.md`,
`0017-hexagonal-conventions.md`,
`0018-events-and-metering.md`,
`0019-streaming-and-rag.md`.

## Context

ADR 0011 Decision 3 made LGTM (Loki/Grafana/Tempo/Mimir) the
platform-wide observability layer, but framed it as complementary to TB
CE's own monitoring (`MONITORING_ENABLED=true`,
`ThingsboardMonitoringApplication`, Prometheus + Grafana, Rule Engine
alerting), which it said is "not reconfigured or removed by this
decision". ADR 0011 itself already records why that framing is weak:
TB CE's stack sees only TB CE's own process health, and cannot see the
`services/*` applications, Kafka, APISIX, or the Python RAG platform.

ADR 0012 Decision 5 established `X-Correlation-ID` as a platform-level,
server-issued business identifier, deliberately distinct from W3C Trace
Context (`traceparent`), with APISIX as the issuing edge and an
override-not-honor rule for client-supplied values. ADR 0012 already
calls the two "complementary"; it does not say how they travel together.

The repository owner has now ratified that LGTM **replaces** TB CE's
internal monitoring as the authoritative enterprise plane, and that the
two identifiers are carried together and never merged.

## Decision

1. **LGTM/OTel is the authoritative enterprise observability plane**
   for traces, logs, and metrics across TB CE, every `services/*`
   application, Kafka, APISIX, and the AI/RAG cluster. Alerting, SLOs,
   incident evidence, and dashboards of record are defined in LGTM only.
2. **TB CE's internal monitoring is local-debug only.** It may remain
   available to engineers debugging a TB CE instance, but it is not a
   source of truth for alerting, SLOs, capacity, or audit, and no
   deployment profile depends on it. TB CE's own metrics continue to
   reach Mimir by remote-write, as ADR 0011 already describes.
3. **Two identifiers, carried together, never merged.**
   - `traceparent` (W3C Trace Context, managed by OpenTelemetry) is
     *lineage*: which technical operation caused which.
   - `X-Correlation-ID` (ADR 0012 Decision 5) is *business correlation*:
     one server-issued value for one business request or event chain.
   - Neither is derived from, stored as, or substituted for the other.
     A log line, span, or event may carry both; it never carries one in
     the other's slot.
4. **`X-Correlation-ID` propagation carriers**, all four required:
   - **OTel baggage**, under one key defined once in `contracts/`
     (key name fixed at contract-authoring time, not here);
   - **span attribute** on every span a service creates for the request;
   - **log MDC**, so every log line in Loki carries it;
   - **Kafka message header**, not the payload (ADR 0012 Decision 5),
     alongside the `traceparent` header OTel's Kafka instrumentation
     writes.
   HTTP/gRPC continue to carry it as the `X-Correlation-ID`
   header/metadata key per ADR 0012.
5. **Origin rules are unchanged.** APISIX issues and overrides
   `X-Correlation-ID` (ADR 0012 Decision 5). Each service's trust-
   boundary behaviour is configuration, not code (the
   `iotee.identity.correlation.trust-boundary` property introduced on
   `fix/m9-conformance-followups`, not yet on `main`).

## Consequences

- ADR 0011 Decision 3's "not reconfigured or removed" clause no longer
  describes the enterprise target. ADR 0011's body is not rewritten;
  its Status block carries a pointer to this ADR.
- Blocked: TB CE v4.3.1.5 OTel trace coverage is unverified.
  Native emission without source modification has not been proven.
  Per CLAUDE.md, external verification unavailable is recorded as
  blocked, not passed. Track A follow-up required before this ADR's
  trace-coverage claim is considered complete.
- Every service template (C1 onward) must ship OTel instrumentation and
  MDC wiring as part of "done", not as a later add-on.
- Baggage propagates to every downstream call, including third parties.
  Outbound adapters to systems outside the platform (e.g. the payment
  gateway in `0018-events-and-metering.md`) must strip platform
  baggage at egress. Baggage must never carry PII, tenant secrets, or
  anything beyond the correlation ID (ADR 0011 Decision 4).
- Deployment artifacts must include an OTel Collector and LGTM for every
  profile, including K3s, since there is no longer a TB-CE-only fallback.

## Open questions (not decided here)

- Blocked: TB CE v4.3.1.5 OTel trace coverage is unverified.
  Native emission without source modification has not been proven.
  Per CLAUDE.md, external verification unavailable is recorded as
  blocked, not passed. Track A follow-up required before this ADR's
  trace-coverage claim is considered complete.
  Attaching the OTel Java agent is the candidate mechanism that keeps
  ADR 0011 Decision 2's no-fork rule; until it is proven, TB CE is
  covered by logs (Loki) and metrics (Mimir) only.
- Whether APISIX honors or restarts an inbound client `traceparent` is
  not decided here (ADR 0012 decides only `X-Correlation-ID`).
- How a TB-CE-originated event obtains its correlation ID remains ADR
  0012 Risk 2's open question.

## Alternatives rejected

- **LGTM complementary to TB CE monitoring** (ADR 0011's original
  framing) -- rejected by ratification: two planes of record.
- **A single merged identifier** (reuse the trace ID as the correlation
  ID, or vice versa) -- rejected: lineage and business correlation have
  different trust and lifetime rules (ADR 0012 Decision 5's override
  rule does not apply to trace IDs).

## Cross-references

ADR 0011 Decisions 2, 3, 4 and Risk 2; ADR 0012 Decision 5; ADR 0013
(each service owns its own correlation and OTel wiring -- no shared
observability runtime JAR); `0018-events-and-metering.md`
(Kafka header carriage); `0019-streaming-and-rag.md` (Flink
must preserve both headers); `0017-hexagonal-conventions.md`
(propagation lives in adapters, not domain).

