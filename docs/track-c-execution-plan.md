# Track C execution plan

Status: proposed -- living planning document, not an ADR. It sequences work that
ADRs 0015-0019 already decide; where this plan and an ADR disagree, the
ADR wins. Drafted 2026-09-25 against `main` at `9801589` and the ADR
numbering branch `docs/adr-assign-numbers` (`8d9d202`, not yet merged).

## 1. Scope and inputs

Inputs: the CTO strategic brief for M9 -> Track C (hexagonal core,
IAM-agnostic OIDC, event-driven metering, Flink-to-RAG, tiered brokers,
edge-to-cloud deployment), and the ratified Track C ADRs:

| ADR | Decides |
| --- | --- |
| 0015 observability authority | LGTM/OTel is the enterprise plane; `traceparent` + `X-Correlation-ID` carried together, never merged |
| 0016 tenancy and identity | Pooled `tenant_id` + Postgres RLS (`SET LOCAL app.tenant_id`); IAM-agnostic OIDC; service is the trust boundary; 2-tier RBAC |
| 0017 hexagonal conventions | `domain` / `application` / `port.in` / `port.out` / `adapter.*` / `config`; dual REST+gRPC transport; per-adapter vendor bans; Postgres adapter only |
| 0018 events and metering | Transactional outbox + relay; idempotent consumers; `usage-event v1`; broker-swap contract; Kafka + Redpanda CI conformance; `PaymentGateway` port |
| 0019 streaming and RAG | Flink as semantic synthesizer (core/AI cluster, never edge); mandatory redaction; pgvector in production, ChromaDB dev/K3s only; TB CE downstream of Kafka |

## 2. Assessment of the brief

The brief is coherent: one hexagonal mechanism (port + single adapter +
ArchUnit vendor ban) delivers every swap it asks for -- database, broker,
IAM provider, payment provider, transport. One event backbone feeds
metering, Flink/RAG, and TB CE's rule engine; the dual-ID propagation of
ADR 0015 is what makes that backbone traceable end to end.

### 2.1 Brief vs. ratified decisions (the ADRs govern)

| Brief | Ratified | Consequence |
| --- | --- | --- |
| Hot-swap Postgres/Oracle via JPA/Hibernate/jOOQ | Postgres adapter only; Oracle is port-level optionality (ADR 0017 Decision 5) | RLS and pgvector are Postgres-specific; an Oracle adapter is a funded project, not a config switch. "Swap" means build/deploy time, never runtime |
| ChromaDB as the vector store | pgvector in production; ChromaDB dev/K3s only (ADR 0019 Decision 5) | One database engine to operate and secure |
| Kafka/Pulsar | CI conformance on Kafka + Redpanda (ADR 0018 Decision 5) | Pulsar is not Kafka-protocol-native; supporting it needs its own adapter, conformance run, and decision |

### 2.2 Gaps -- no ADR yet

| Requirement from the brief | Current state |
| --- | --- |
| Zero Trust: mTLS between services, OPA policy | Undecided |
| Tiered brokers: edge MQTT buffer -> Kafka | Tier-1 buffer not designed (ADR 0011 Risk 6; ADR 0019 open items) |
| Feature toggles: operation off for tenants, on for Product Operators | Needs its own ADR; must stay out of the billing path (ADR 0011 Decision 6; ADR 0018 "Conflicts") |
| OWASP / secure by design | No baseline ADR; ADR 0014 (supply-chain scanning) is reserved and covers only part |
| Edge-to-cloud deployment (K3s -> RKE2/OpenShift -> EKS/GKE/AKS) | No ADR; `deploy/` does not exist. OpenShift restricted SCC (non-root, arbitrary UID) must constrain images from the first chart |
| Disposable Migration Studio (SQLGlot/Calcite, rule-node and BIRT mapping) | Explicitly deferred to its own ADR (ADR 0019 open items) |

### 2.3 Blocked external verifications (blocked, not passed)

- TB CE v4.3.1.5 consuming platform Kafka topics into its rule engine
  without source modification: unverified; `docs/tb-ce-inventory.md`
  section 2.2 found TB's integration framework is PE/Cloud-only. Affects
  both "TB CE downstream of Kafka" and Migration Studio's rule-node
  mapping target.
- TB CE v4.3.1.5 native OTel trace emission: unverified (ADR 0015 open
  questions).

## 3. Entry conditions and progress (updated 2026-09-25)

C0 gate, as it stood when this plan was drafted, and what closed it:

| Condition | Status |
| --- | --- |
| ADRs 0015-0019 numbered and accepted | Done: PR #19 (numbers, `331bf89`) and PR #20 (accepted, `bd8c98e`) |
| `main` green in CI after PRs #16-#18 | Done: PRs #20, #21 and #23 each passed CI on top of it |
| `CLAUDE.md` intact | Done: the truncated working copy was discarded; the committed file is authoritative |

Progress against section 4:

| Step | Status |
| --- | --- |
| C0 | Done |
| C1 | Done: PR #21 (`8d95e3c`), CI run #103 green on `878f568` |
| C2 | Next |
| C3-C7 | Not started |

Outside the C-step sequence:
- Private-cloud IaC (`deploy/`) merged in PR #23 (`21c325f`), then re-laid
  out in layers: Layer 0 `deploy/00-infra/private-hyperv` (single Hyper-V
  host, NAT switch, 1 control plane + 2 workers) replaces
  `deploy/provisioning`; Layer 1 `deploy/01-k8s-engine/rke2-ansible`
  (CIS-profile RKE2, restricted PSS, secrets encryption; engine only)
  replaces `deploy/configuration`, and kube-vip moves to Layer 2 as a
  services-only DaemonSet. The ADR is still in draft
  (`XXXX-proposed-private-cloud-infrastructure.md`). It covers the
  private-cloud part of section 2.2's edge-to-cloud gap.
- Flink and Elasticsearch version management merged in PR #24 (`f04851c`).

## 4. Execution steps

Step labels follow the ADRs' own references (ADR 0017 "C1 work", ADR 0016
"C3 design", ADR 0018 "C4 design"). Each step is its own PR with its own
green CI run.

| Step | Scope | Exit criterion |
| --- | --- | --- |
| **C0 Gate** | Restore `CLAUDE.md` (HEAD or the complete revised version); merge ADR numbering and fix H1/status lines; fresh green CI on `main`; open proposed ADR stubs for every gap in 2.2 | `main` green on GitHub-hosted CI after PR #18; ADRs 0015-0019 accepted; gap ADRs exist as proposals |
| **C1 Hexagonal skeleton** (ADR 0017) | Refactor `services/identity` into `domain` / `application` / `port.in` / `port.out` / `adapter.in.rest` / `config`; constructor injection; Spring wiring in `config` only; per-adapter vendor bans for `org.apache.kafka..`, persistence providers, `org.keycloak..`, `com.stripe..`, `io.grpc..`, `org.springframework.web..` via `ArchRules.noClassesOutsideSubpackageDependOnPackages` | ArchUnit proves inward-only dependencies; every ban has a negative fixture test and a vacuity tripwire |
| **C2 Observability foundation** (ADR 0015) | OTel instrumentation in every service from C1 on; correlation baggage key defined once in `contracts/`; `X-Correlation-ID` on baggage, span attribute, log MDC, Kafka header; LGTM stack in the K3s dev profile | A test proves one request's logs and spans carry both `traceparent` and `X-Correlation-ID`, each in its own slot |
| **C3 IAM-agnostic identity** (ADR 0016) | `port.out` for token verification and claim mapping to a framework-free `Principal`; adapter on Spring Security OAuth2 resource server with standard JWT (no Keycloak SDK); claim names are configuration; 2-tier role set; Keycloak only in per-service Testcontainers ITs; design Tier 1's audited cross-tenant path | Swapping the OIDC provider in tests is configuration-only; a request carrying only APISIX identity headers is rejected |
| **C4 Persistence and tenancy** (ADRs 0016, 0017) | First `adapter.out.persistence` (PostgreSQL + Flyway); `SET LOCAL app.tenant_id` per transaction; `ENABLE` + `FORCE ROW LEVEL SECURITY` on tenant-owned tables | Testcontainers test shows RLS blocks a cross-tenant read with the application-level check deliberately bypassed |
| **C5 Event backbone** (ADR 0018) | Outbox table + relay; per-consumer dedupe tables; `usage-event v1` (Protobuf, envelope `idempotency_key`) in `contracts/`; `adapter.out.messaging`; CI conformance on Kafka and Redpanda | Same suite green on both brokers; redelivery produces no second effect |
| **C6 Transport-swap proof** (ADR 0017 Decision 3) | `adapter.in.grpc` over the same `port.in` as REST; stubs generated per service from `contracts/`. May run in parallel from C1 | The same command reaches the same handler over REST and gRPC; the two adapters share no code |
| **C7 Deploy foundation** | `deploy/` Helm chart for `services/identity`, K3s values, Argo CD Application; OpenShift-restricted-SCC-compatible `securityContext`; no cloud-vendor data services | Chart installs on k3d in CI; the same chart renders unchanged with EKS/GKE/AKS values files |

### 4.1 Ordering rationale

- C1 first: every later step adds an adapter, and adapters need the port
  layout to exist.
- C2 and C3 before C4: tenant scoping (RLS) needs a trusted `Principal`,
  and the trace plane should observe persistence from its first query.
- C5 after C4: the outbox must share a transaction with the state change.

## 5. Next milestone (after C5 and the gap ADRs)

- Billing domain consuming `usage-event v1`; `PaymentGateway` port with a
  Stripe adapter (`com.stripe..` banned elsewhere).
- Flink job -> redaction -> pgvector -> RAG agent, core/AI cluster only.
- Tier-1 edge MQTT buffer (after its ADR).
- mTLS/OPA (after the Zero Trust ADR).
- Migration Studio pipeline (after its own ADR; authorized export,
  inventory, shadow comparison, and approval per `CLAUDE.md`).

## 6. Assumptions vs. observations

- **Observed** (read from the repository on 2026-09-25): ADR contents,
  branch and commit state, `services/identity/README.md` CI record,
  `CLAUDE.md` working-tree truncation.
- **Assumed / not verified here**: GitHub Actions run history (not
  reachable from the authoring environment); TB CE capabilities listed in
  2.3; step sizes and parallelism, which are planning estimates.
