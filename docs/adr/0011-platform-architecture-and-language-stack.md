# ADR 0011 -- Platform architecture and language stack: Java/Spring platform, Python for RAG, ThingsBoard CE as an upgradeable compatibility component

Status: proposed -- pending explicit human agreement. Per the repository
owner's instruction, no further platform code is written (in either
language) until this ADR is agreed; M9 and beyond do not start before
then.

## Numbering note (read this first)

This ADR was requested as `docs/adr/0009-platform-architecture-and-
language-stack.md`. That number is already taken by
`docs/adr/0009-m8-deployment-studio.md` on `main`, and `0010` is already
used by `docs/adr/0010-worm-s3-adapter-graduation.md` on an unmerged
branch (`feature/worm-s3-adapter-graduation`). To avoid a collision
regardless of which branch merges first, this document is numbered
**0011**. If the repository owner prefers different final numbering once
branches are reconciled, renumbering this file (and updating its
in-repo references) is a small follow-up, not a rewrite -- flagged again
under "Missing inputs," below.

## Context

This repository (IOT-EE) modernizes a legacy enterprise, multi-tenant
IoT platform built on an old ThingsBoard CE (TB CE) version. The
founding brief -- provided directly by the repository owner across two
messages -- is the architectural source of truth for bounded contexts,
the migration model, TB CE's role as a compatibility component, Studio
tooling, deployment profiles, security rules, and milestone
definitions. That brief was originally language-agnostic; a follow-up
clarification from the repository owner fixed the actual language
mandate: **Java 17 + Spring Boot/Spring Cloud is the platform**, not
Python.

This matters because M0-M8 of this repository were already built in
Python, before that clarification. Per the repository owner's explicit
instruction, that work's status changes here: **M0-M8's Python code is
a specification / reference implementation, not the platform
codebase.** What it proved out -- domain contracts, ADRs 0001-0009 (and
the post-M8 graduation, ADR 0010), provider-neutral contracts, an
append-only evidence ledger, the mechanical isolation-gate discipline
enforced by `scripts/check.py`, an RBAC permission catalog, a WORM/
evidence contract, an immutable deployment-profile registry, and a
billing ledger -- is language-portable and must guide the Java
implementation. The Python source itself is not the deliverable.

## Decisions

### 1. Polyglot architecture: Java platform, Python for RAG and standalone adapters only

Java 17 + Spring Boot/Spring Cloud is the platform language for:

- ThingsBoard CE extensions: custom rule nodes implementing
  `TbNode`/`@RuleNode`, and custom integrations extending
  `AbstractIntegration` over gRPC.
- Every core bounded context named in the founding brief:
  identity/tenant, device/connectivity, asset/tank, telemetry
  ingestion, rules/automation, alarm/command, reporting, audit/
  evidence, firmware, usage metering, optional monetization, Migration
  Studio, and Deployment Studio.
- General Spring Cloud microservices (service discovery, config,
  client-side load balancing, resilience patterns) wherever a
  cross-cutting platform concern needs one.

Python (FastAPI + server-issued correlation-ID propagation) is scoped,
going forward, to exactly two things: the future RAG subsystem, and
standalone adapters. "Standalone adapter" here means the same shape
this repository's own `adapters/worm_s3` package already proved: a
real-backend implementation of a stable contract, isolated from
whatever calls it, with its own optional dependency footprint -- not a
general license to build platform services in Python.

Rationale: ThingsBoard CE is itself a Spring Boot application, so
in-process extension requires JVM interop; Spring Cloud is a mature
fit for the required microservice patterns; and the mandated deployment
tooling (Ansible/Terraform-or-OpenTofu/Helm/ArgoCD, Kafka clients,
gRPC, an Istio mesh) is enterprise-Java-conventional. Python's actual
strengths -- RAG/LLM tooling, ad hoc data processing -- are kept
exactly where they add value and nowhere else.

### 2. ThingsBoard CE: a compatibility component, extended in Java, never forked, upgraded with minimal friction

TB CE is not the platform's source of truth. It is a bounded,
replaceable compatibility layer providing device connectivity (MQTT/
HTTP/CoAP transports), a rule engine, and dashboard primitives the
legacy system already depends on.

Extension model: custom rule nodes (`TbNode`/`@RuleNode`) and custom
integrations (`AbstractIntegration`, over gRPC) run *inside* TB CE's
own process/plugin model. This is extension, not forking: no
modification of TB CE's own source tree; an upgrade stays a dependency-
version bump, never a merge-conflict exercise against a divergent fork.
Everything TB CE does not natively provide (billing, evidence/WORM,
RBAC/ABAC beyond TB's own tenant model, Deployment Studio, Migration
Studio) is built as separate Spring Boot/Cloud services alongside it,
talking to TB CE over gRPC/events -- never by editing TB CE internals.

This decision is elevated by the repository owner from a general
principle to a binding upgrade-friction requirement, addressed fully in
Decision 9 below: the customization surface stays deliberately thin and
declaratively described, so that adopting a new TB CE version is a
reapply-the-customization-set exercise, not a re-implementation.

### 3. Env-agnostic infrastructure layer (mandatory stack, must stay swappable)

- **MQTT brokers**: Mosquitto / HiveMQ / EMQ -- env-agnostic,
  replaceable; MQTT itself stays broker/L4.
- **Gateway**: APISIX -- L7 north-south (REST/gRPC/WebSocket/SSR);
  L3/L4 is retained separately, not absorbed into APISIX.
- **Internal events**: Kafka + MirrorMaker as the event backbone --
  explicitly **not** an immutable compliance database (that role stays
  with `services/evidence`'s real WORM backend, per Decision 8 in
  `docs/adapters-worm-s3.md`'s precedent). Idempotent consumers
  throughout; no distributed atomic-transaction claim across Kafka and
  any other system (mirrors this repo's own established rule never to
  claim atomicity between ThingsBoard and Kafka).
- **Storage**: PostgreSQL (OLTP) + MinIO/S3 with Object Lock (WORM) +
  an eventstream database.
- **Observability**: LGTM (Loki/Grafana/Tempo/Mimir).
- **Mesh/load balancing**: Istio; L3/L4/L7.
- **Inter-service transport**: gRPC internally; REST + Spring Cloud
  LoadBalancer for external HTTP.
- **Deployment**: Ansible + Terraform/OpenTofu + Helm + Argo CD, across
  on-prem upstream Kubernetes and managed EKS/AKS/GKE.

"Env-agnostic" is only a real property if something mechanically stops
a service from hard-coding a specific vendor SDK call. That mechanism
does not exist yet for Java and is tracked as Risk 1, below -- this
decision states the intent; it does not yet claim the guarantee is
enforced.

### 4. Constitutional engineering rules (unchanged, now binding for Java too)

Carried forward from the founding brief and this repository's own
existing contract, verbatim in spirit:

- No secrets, raw PII, payment data, private keys, device credentials,
  or production exports in source, Git, event payloads, logs, metrics,
  prompts, or test fixtures. Submitted secrets go to the configured
  vault once; only a secret reference plus non-sensitive validation
  metadata is ever persisted.
- Server-issued correlation IDs; causation/idempotency data is
  preserved end-to-end. Event contracts are versioned, additive by
  default, and compatibility-tested.
- Firmware artifacts are signed, device-verified, and hardware-
  compatible, delivered via HTTPS/object storage or another supported
  transport. WebSockets report rollout progress only -- they never
  carry the firmware binary.
- No destructive command, infrastructure apply, deployment, production
  secret rotation, or test/policy-gate bypass without explicit human
  approval. No claim of cloud/compliance/DR/migration completion based
  solely on local tests.
- Multi-tenant: RBAC + ABAC + mTLS/JWT/SSO (Decision 5). Tenant-admin
  grants access and creates operator roles; sys-admin opens SSH
  channels via a bastion VM.
- Pay-as-you-go metering is cross-cutting and always-on; optional
  monetization subscribes to already-finalized usage events and never
  blocks telemetry/alarm/command (Decision 6).
- Claude Enterprise development contract stays in force: PR + CI +
  human review for every change, no agent self-approval, no raw PII/
  credentials/dumps/vault secret values in prompts, Git, app databases,
  events, logs, or audit evidence -- synthetic data only. A stage is not
  complete without code, tests, docs, deployment artifacts, and passing
  stage + full regression gates.

### 5. Multi-tenancy and security model

RBAC provides the coarse, catalog-driven role/permission model --
conceptually the same shape as this repo's own
`domain_core.rbac.PERMISSION_CATALOG`, ported to Java as a permission
registry/enum rather than a Python string-set. ABAC layers attribute-
scoped decisions on top (tenant ID, resource ownership, environment
tier, deployment profile) where a role alone is too coarse. Service-to-
service traffic is authenticated via mTLS through the Istio mesh;
external/edge identity is JWT/SSO, validated at the APISIX edge before
a request ever reaches a Spring service. Tenant-admin accounts scope
and grant operator-level roles within their own tenant; sys-admin,
lower-level access is only ever reached through a bastion VM's SSH
channel, never directly.

### 6. Pay-as-you-go / optional monetization

Carries forward M7's exact design intent, now as a Java/Kafka-native
event-driven service: metering is cross-cutting and always-on
(certified usage counts, mirroring `billing.metering`'s design).
Monetization itself stays optional and default-off, and is
structurally a pure Kafka **consumer** of already-finalized
`UsageMetered` events -- never a synchronous dependency of the
telemetry/alarm/command hot path. M7's explicit prohibition on any
`is_feature_entitled`-shaped function carries forward unchanged:
entitlement/feature-gating stays out of the billing path entirely
unless a future ADR revisits it on its own terms.

### 7. Deployment profiles

Four target topologies, each a first-class deployment target rather
than an afterthought, extending M8's `deployment_studio.profiles`
model (`Environment`/`Provider`/`Region`/`Tier`, immutable versioned
registry, default-deny lookup) with a new **Topology** dimension on
that same profile record shape -- not a parallel concept:

1. **Modular-monolith (lab/pilot)** -- fewer moving parts; a single
   Spring Boot application with TB CE co-located, for evaluation and
   small pilots.
2. **Distributed, single-cluster (production default)** -- one
   Kubernetes cluster, an Istio mesh, Kafka, and every service
   independently deployable. Stated as the default target for real
   tenants.
3. **Multi-cluster** -- for larger or geographically distributed
   tenants; Kafka MirrorMaker replicates events across clusters, and
   Istio's multi-cluster mesh federation ties them together.
4. **Edge** -- constrained or disconnection-tolerant deployments (e.g.
   gateway hardware local to the tanks). Under-specified in the
   founding brief; flagged as Risk 6 and a Missing Input below rather
   than designed here.

### 8. TB CE upgrade strategy: minimal customization surface, `cfg.yaml`-driven tool swaps

Added per the repository owner's explicit follow-up. TB CE and this
platform's own Java code must both be upgradeable with minimal
friction, and that requirement is now binding, not aspirational:

- **TB CE stays as close to upstream as possible.** Customizations are
  kept deliberately minimal: thin custom rule nodes, thin custom
  integrations, and configuration -- never a source-level patch to TB
  CE itself.
- **External-over-internal, by default.** Wherever a behavior *can*
  live outside TB CE and talk to it over its own API/event surface
  (gRPC integrations, its REST API, its own Kafka-facing rule nodes),
  it does -- it is built as one of the `services/*` Spring
  applications, not as more TB CE-internal logic. A behavior only
  becomes TB CE-internal customization when it must run in TB CE's own
  process (e.g. a rule-node decision that needs sub-millisecond,
  in-process access to TB CE's own rule-chain context).
- **The customization surface is declaratively described, not just
  minimal.** The set of custom rule nodes, integrations, and TB CE
  configuration this platform depends on is recorded in a versioned
  manifest (part of `cfg.yaml`, below, or a sibling file it references)
  -- so adopting a new TB CE version is: (1) stand up the new TB CE
  version upstream, unmodified; (2) reapply the recorded customization
  set against it; (3) run the existing shadow-parity mechanism (M3's
  design, Decision/Risk 4) to confirm behavior is unchanged before
  cutover. This is a reapply exercise against a manifest, not a
  re-implementation project.
- **Our own Java bounded contexts get the same discipline.** Each
  `services/*` bounded context is built behind a clean, published
  contract (an event schema, a gRPC/REST API, or both); a service's
  internals can change freely as long as its contract's compatibility
  tests keep passing, mirroring M7's billing-isolation-gate discipline
  generalized to every service.
- **Tool swaps are a `cfg.yaml` concern, not a code concern.** Kafka,
  APISIX, the Kubernetes distribution, LGTM, the payment/monetization
  backend, storage (which S3-compatible provider, which Postgres host),
  and the MQTT broker are all named in a single, short, schema-
  validated `cfg.yaml` surface per environment. Swapping one of these
  tools is a `cfg.yaml` change plus a matching `adapters/*` module
  (Decision 3/Risk 1) -- it must never require touching business logic
  in `services/*` or `tb-extensions/*`.

  A representative (illustrative, not final) `cfg.yaml` shape:

  ```yaml
  # cfg.yaml -- validated against a JSON Schema in common/config-schema/
  platform:
    deploymentTopology: distributed-single-cluster   # modular-monolith | distributed-single-cluster | multi-cluster | edge
  thingsboard:
    version: "<pinned TB CE version>"
    customizationManifest: tb-extensions/manifest.yaml   # the declarative rule-node/integration/config set this ADR requires
  messaging:
    provider: kafka                                   # kafka (+ mirrormaker for multi-cluster)
  mqtt:
    provider: mosquitto                                # mosquitto | hivemq | emq
  gateway:
    provider: apisix
  mesh:
    provider: istio
  storage:
    oltp: postgresql
    objectLock: minio                                 # minio | s3
    eventstream: <eventstream-db-provider>
  observability:
    stack: lgtm
  deploy:
    provisioning: [ansible, terraform]                 # terraform | opentofu, interchangeably
    packaging: helm
    delivery: argocd
  payments:
    provider: <configured-provider-or-none>            # monetization stays optional per Decision 6
  ```

  The exact schema, its validation tooling, and where `tb-
  extensions/manifest.yaml` physically lives are open design work for
  M9, not settled here -- this ADR fixes the *principle* (tool identity
  is config, never code) and a representative shape, not the final
  contract.

### 9. Status of M0-M8 Python work

Specification and reference implementation only, effective
immediately -- not the platform codebase. Nothing further is built in
Python in this repository except the two Python-scoped areas from
Decision 1 (RAG, standalone adapters) and whatever documentation work
supports the Java transition. Its lasting value, to be ported
*conceptually* into Java (not translated line-by-line):

- The provider-neutral contract pattern itself (an abstract contract
  plus an in-memory test double plus, where graduated, a real adapter
  kept isolated behind it) -- `migration_studio.vault.VaultProvider`
  through `evidence.worm.WormStore` through `adapters.worm_s3
  .S3WormStore` are all one pattern, repeated seven times over.
- The append-only, hash-verified evidence/WORM ledger shape.
- The RBAC permission-catalog shape (a single source-of-truth
  registry, not scattered string literals).
- The immutable, versioned, default-deny deployment-profile registry.
- The billing ledger's shape: effective-dated pricing, reversal-only
  correction (never a raw edit), and a single default-off flag-
  resolution function as the only gate.
- Most importantly, **the mechanical isolation-gate discipline itself**
  (`scripts/check.py`) -- a Java-native equivalent (ArchUnit, Risk 1)
  must exist before the Java platform can claim the same guarantees
  the Python spec already proved out; dropping this discipline when
  the language changes would be a real regression, not a simplification.

`scripts/check.py` and the Python test suite remain in this repository,
continuing to pass, as living, executable documentation. They are not
deleted. They stop being "the gate that blocks platform progress" once
a Java-native gate (Decision 3/Risk 1) exists, but they keep gating
their own content indefinitely.

## Consequences

- **Easier**: real, in-process TB CE interop instead of an external
  bridge; direct access to the Java/Spring ecosystem's enterprise
  deployment tooling; a more conventional stack for enterprise Java
  hiring and onboarding; a TB CE upgrade path that is a config-reapply
  exercise rather than a fork-merge exercise (Decision 8).
- **Harder**: the Python isolation-gate discipline has no drop-in Java
  equivalent yet -- it must be rebuilt with ArchUnit and build-tool
  conventions before the Java platform can claim the same mechanical
  guarantees (Risk 1). Two platform languages (Java + Python-for-RAG)
  raise correlation-ID propagation, schema-contract sharing, and CI
  complexity (Risk 2). A declarative TB CE customization manifest and a
  validated `cfg.yaml` schema are both new artifacts this repository
  does not yet have (Decision 8).
- **To revisit**: whether Python's RAG/adapters boundary needs its own
  mechanical isolation gate (ArchUnit has no reach into a separate
  Python service); the final `cfg.yaml` schema and where the TB CE
  customization manifest lives; this ADR's own final numbering once
  branches are reconciled (see Numbering note).

## M0-M8 -> Java/Spring mapping

| Stage | Python spec module(s) | Java/Spring disposition |
| --- | --- | --- |
| M0 Foundation | `foundation.contracts` (event/plan validation) | **Port the concept, not the code.** A shared `common` library's event/command envelope validation, likely backed by JSON Schema/Avro/Protobuf rather than hand-rolled validators, used by every service. |
| M1 Migration Studio foundation | `migration_studio.vault/sources/evidence` | **Port directly.** `services/migration-studio`'s real vault integration (e.g. a HashiCorp Vault Java client), source-system registration/authorization lifecycle, and an append-only hash-chained evidence log -- now against a real legacy TB CE/Postgres/BIRT source instead of a synthetic fixture. |
| M2 Domain core | `domain_core.tenancy/units/rbac/assets/devices/commands` | **Port the concept, split by bounded context.** `services/identity` (tenancy, RBAC/ABAC), `services/device`, `services/asset` (assets/units), commands folded into the relevant bounded context (e.g. `services/alarm`) -- Java's bounded contexts are separate deployable services, not one shared package. |
| M3 Ingestion | `ingestion.topics/outbox/kafka`, `domain_core.delivery`, `migration_studio.shadow_parity` | **Largely replaced by TB CE.** Do not rebuild MQTT ingestion, the transport-layer transactional outbox, or a custom Kafka relay -- TB CE's own MQTT/HTTP/CoAP transports and rule engine already own device-facing ingestion. What remains to build: a thin `services/ingestion` bridge exporting TB CE telemetry into the platform's own versioned Kafka event contracts for downstream consumers, and the shadow-parity *comparison contract*, which ports directly even though its shadow source changes to two live TB CE deployments being diffed (Risk 4). |
| M4 Gateway/reporting | `gateway.auth/routes`, `reporting.telemetry_summary/timezone/publication` | `gateway.auth`/`routes` **replaced by APISIX** (JWT/mTLS/SSO, routing, and rate limiting live at the edge; Spring services trust APISIX-validated identity headers rather than reimplementing auth). `reporting.*` **ports directly** as `services/reporting`, a Spring Boot CQRS read-model service with SSR/WebSocket delivery. |
| M5 Firmware | `firmware.signing/provenance/rollout/delivery` | **Port directly** as `services/firmware`: same signing/provenance/staged-rollout/rollback contract shape; the WebSocket-progress-only rule (Decision 4) is unchanged and now Java-native (Spring WebSocket). |
| M6 Evidence/WORM/DR + graduation | `evidence.records/worm/capture/legal_hold/dr`, `adapters.worm_s3` | **Port directly** as `services/evidence`: a `WormStore` interface with a real S3/MinIO Object Lock implementation (AWS SDK v2 or MinIO's Java SDK), redaction/content-hash checks enforced at construction, RBAC-gated legal hold. The Python adapter's isolation/lazy-dependency discipline becomes a build-tool concern (a separate Gradle/Maven module with its own optional dependency) rather than a runtime lazy import. |
| M7 Monetization | `billing.flags/metering/pricing/closure/service` | **Port directly** as `services/monetization` (Decision 6): default-off single-gate flag resolution, certified usage-count metering consuming finalized Kafka events, effective-dated price plans, immutable reversal-only period closure. M7's billing-isolation gate becomes an ArchUnit rule: no other service module may depend on `services/monetization`'s internals, only its published event contract. |
| M8 Deployment Studio | `deployment_studio.profiles/approval/audit/plan/iac/runner/gitops` | **Port directly** as `services/deployment-studio`: immutable versioned profile registry extended with the Topology dimension (Decision 7), RBAC-gated plan lifecycle and audit, IaC-document validation now against real Ansible/Terraform-or-OpenTofu/Helm/ArgoCD manifests, GitOps reconciliation against a real cluster -- the natural next real-backend graduation, directly continuing this repo's own ADR 0010 precedent (a real, read-only GitOps backend was the item ADR 0010 explicitly deferred). |
| Post-M8 graduation | `adapters.worm_s3` | **Direct precedent**, not a milestone to re-map: establishes the adapter-isolation pattern (Decision 3/Risk 1) and the specific Object Lock approach `services/evidence`'s Java adapter should follow. |

## Repo structure proposal

**Recommendation: a polyglot monorepo**, given RAG stays Python.

| Dimension | Polyglot monorepo (recommended) | Separate repos |
| --- | --- | --- |
| Cross-cutting event-contract changes (Java producer + Python RAG/adapter consumer, or vice versa) | One PR, atomically reviewed and gated together | Coordinated multi-repo PRs, versioned contract packages, higher chance of drift |
| ADR/spec-to-implementation traceability | A Java developer implementing `services/evidence` can reference `spec/evidence/worm.py` in the same repository | Requires cross-repo linking; spec can silently go stale |
| Build tooling | Maven/Gradle and Python coexist under one root, needs CI matrix discipline but is a solved problem | Each repo's CI stays simpler in isolation |
| Access control | One set of repo permissions for everyone touching the platform | Can separate RAG-team access from platform-team access if that's an org requirement |
| `scripts/check.py`'s continued role | Keeps gating its own `spec/` content in the same CI run, low friction to keep it green | Runs in its own repo/CI, fully decoupled -- arguably cleaner but loses the side-by-side reference value |

**What happens to the current IOT-EE repo**: it becomes the polyglot
monorepo. The existing M0-M8 Python work relocates to a `spec/`
subtree (see the skeleton below) and keeps its own gate
(`scripts/check.py`) green as living documentation; it is not deleted
and not spun out to a separate "spec-only" repo. The ADR history and
mechanical-gate discipline this repository already has are exactly the
kind of institutional knowledge more valuable staying attached to the
code that supersedes it.

**When the alternative (separate repos) would be preferred**: if
Maven/Gradle-plus-Python CI proves genuinely painful to co-locate, or
if the RAG team needs materially different repository access than the
platform team, or if repository size/history-rewrite concerns make a
single monorepo unwieldy at the organization's actual scale. None of
these are confirmed one way or the other by the founding brief --
flagged under Missing Inputs.

## Proposed Java platform skeleton

```
iot-ee/
├── pom.xml                          # or settings.gradle.kts -- parent/multi-module build
├── common/                          # shared event/command envelope, correlation-id propagation,
│                                     # RBAC/ABAC primitives, cfg.yaml schema, ArchUnit base rules
├── tb-extensions/
│   ├── rule-nodes/                  # custom TbNode / @RuleNode implementations
│   ├── integrations/                # custom AbstractIntegration implementations (gRPC)
│   └── manifest.yaml                # declarative customization set -- Decision 8's upgrade contract
├── services/
│   ├── identity/                    # tenant, RBAC/ABAC, SSO/JWT validation support
│   ├── device/                      # device/connectivity bounded context
│   ├── asset/                       # asset/tank bounded context
│   ├── ingestion/                   # thin TB CE -> Kafka event bridge (M3's surviving slice)
│   ├── rules/                       # rules/automation beyond TB CE's own rule engine, if needed
│   ├── alarm/                       # alarm/command bounded context
│   ├── reporting/                   # CQRS read models, SSR/WebSocket delivery
│   ├── evidence/                    # WORM/audit (M6 port)
│   ├── firmware/                    # signing/provenance/rollout (M5 port)
│   ├── metering/                    # usage metering -- pay-as-you-go, always-on
│   ├── monetization/                # optional billing (M7 port); consumes metering events only
│   ├── migration-studio/            # M1 port + real legacy-TB/BIRT/rule-chain import
│   └── deployment-studio/           # M8 port + Topology dimension (Decision 7)
├── adapters/
│   ├── mqtt/                        # broker-specific clients (Mosquitto/HiveMQ/EMQ), behind a
│   │                                 # broker-neutral interface -- selected via cfg.yaml
│   ├── apisix/                      # APISIX admin-API client / route provisioning
│   └── storage/                     # S3/MinIO Object Lock, PostgreSQL, eventstream DB adapters
├── deploy/
│   ├── ansible/
│   ├── terraform/                   # or opentofu/
│   ├── helm/
│   └── argocd/
├── services/python/
│   └── rag/                         # FastAPI + Correlation-ID -- Python-only going forward
├── cfg/
│   └── cfg.yaml                     # Decision 8's tool-swap configuration surface, per environment
└── spec/                            # this repo's existing M0-M8 Python reference, relocated here;
                                       # scripts/check.py keeps running and gating this subtree only
```

Notes:
- `common/` is where the ArchUnit rules that make the env-agnostic
  boundary (Decision 3) and the bounded-context contract discipline
  (Decision 8) mechanically real actually live -- a shared test-fixture
  module every service's build depends on, mirroring
  `scripts/check.py`'s single-source-of-truth constant-list pattern.
- `adapters/` sits at the top level, not nested per-service, mirroring
  this repo's own `adapters/worm_s3` isolation precedent: a real
  backend implementation lives outside the bounded-context service that
  depends on its contract, never inside it.
- `tb-extensions/manifest.yaml` and `cfg/cfg.yaml` are the two artifacts
  Decision 8's upgrade-friction requirement actually depends on; their
  final schemas are M9 design work, not fixed here.
- `spec/` keeps `scripts/check.py` running in CI, unmodified in spirit
  -- it stops gating Java progress but keeps gating its own content.

## Risks and open questions

1. **Env-agnostic boundary enforcement in Java has no mechanism yet.**
   Proposed: ArchUnit rules in `common/src/test`, one rule per boundary
   (e.g. "no class outside `adapters..` may depend on
   `software.amazon.awssdk..`"), run as a required step in every
   service's own build, plus a parent-level ArchUnit suite scanning the
   whole multi-module build for a bypass via a transitive/shaded
   dependency. This is the direct Java analog of `check.py`'s
   `_check_no_forbidden_imports`/`_check_forbidden_package_imports`,
   but it needs its own design and implementation pass before Decision
   3's "must stay swappable" claim is actually enforced rather than
   merely intended.
2. **Correlation-ID propagation across five different systems.**
   APISIX (an HTTP header at the edge), Java services (context
   propagation across gRPC and Kafka headers -- likely Micrometer
   Tracing), Kafka itself (must carry the ID as a message header, not
   just in the payload, so MirrorMaker replication and existing tooling
   see it uniformly), Python/FastAPI (already scoped for this per the
   founding brief, but needs to agree on the same header/Kafka-header
   convention), and TB CE (does its rule-chain/integration framework
   expose a hook to inject/read a correlation ID on ingress? -- needs
   checking against the actual TB CE version in use, not assumed).
3. **Migration Studio's BIRT and legacy rule-chain import mechanics are
   undesigned.** Intentionally out of scope for this ADR (per "do not
   write platform code"), but two concrete gaps are worth naming: (a)
   BIRT report definitions are XML (`.rptdesign`); a real importer needs
   a BIRT-definition parser (Eclipse BIRT's own Java libraries are a
   natural fit, reinforcing Decision 1) and a mapping to
   `services/reporting`'s own report-definition shape -- undesigned;
   (b) legacy TB rule-chain JSON import needs a node-type compatibility
   matrix (which legacy rule-node types map to which new custom
   `TbNode`/`@RuleNode`, and which have no equivalent and need manual
   conversion) -- this matrix does not exist yet and is a concrete M9
   prerequisite.
4. **Shadow rule-parity against TB CE means two live TB CE deployments
   being diffed, not one system compared to a synthetic double.** M3's
   Python spec proved the *comparison contract* in the abstract; running
   it for real means legacy TB CE and new-platform TB CE both
   processing the same input, diffed field-by-field with
   `migration_studio.shadow_parity`'s existing tolerance-scoped design.
   Open: does this require a replay/fan-out mechanism to feed identical
   input to both (not designed yet), and does shadow comparison run in
   staging only or in a controlled production shadow -- given the
   constitutional rule against claiming migration completion from local
   tests alone?
5. **Service-vs-module boundary is not fully settled.** The skeleton
   lists roughly a dozen `services/*` directories; whether each is a
   truly independent Spring Boot deployable, or some are logically
   separate modules co-deployed for the modular-monolith profile
   (Decision 7), needs a build-engineering follow-up -- the same module
   set must be able to produce both a single fat JAR (lab/pilot) and N
   independent service JARs (production default) if Decision 7's four
   profiles are to share one codebase rather than diverging.
6. **The edge deployment profile is named but not designed.**
   Store-and-forward semantics, local rule-evaluation scope, and
   reconciliation-on-reconnect with the core cluster's Kafka/evidence/
   RBAC state are all real questions with no answer yet in the founding
   brief.
7. **TB CE version and license are not confirmed.** This ADR assumes
   "ThingsBoard CE" generically; the actual version (and Apache 2.0
   licensing terms for the extension points this ADR relies on --
   `TbNode`/`AbstractIntegration`) has not been pinned against a
   specific release, which matters for API stability of Decision 8's
   whole upgrade strategy.
8. **Kafka-as-backbone vs. evidence-as-compliance-record is a subtlety
   worth explicit confirmation.** The brief is explicit that Kafka is
   "an event backbone, not an immutable compliance DB" -- this ADR
   reads that as: Kafka carries the replayable event stream for
   cross-service integration, while `services/evidence`'s real Object
   Lock-backed store remains the actual immutable record, exactly as M6
   already established. Flagged only because it affects
   `services/ingestion`'s retention/replay design and is worth the
   repository owner confirming explicitly rather than this ADR assuming
   it.

## Missing inputs needed before M9+ can start

- The actual ThingsBoard CE version/edition in use or targeted, and
  confirmation of its extension-API surface's availability and
  stability at that version (Risk 7).
- A legacy-TB-CE rule-node-type inventory, to build the compatibility/
  conversion matrix Risk 3 names.
- The legacy BIRT report inventory (how many `.rptdesign` files, their
  complexity), to scope the Migration Studio importer.
- A decision on the shadow-parity mechanism (Risk 4): is there a
  non-production environment where legacy and new-platform TB CE can
  both receive replayed telemetry, and who owns provisioning it?
- Org/team constraints bearing on the repo-structure decision: does the
  RAG team need repo access separate from the platform team? Is there
  an existing organizational precedent for (or against) mixed Maven/
  Gradle-plus-Python monorepos?
- Confirmation of which deployment profile (Decision 7) is the actual
  near-term target -- "production default" is stated as distributed
  single-cluster, but if the first real deployment is a pilot,
  Migration Studio and Deployment Studio's earliest acceptance criteria
  should target the modular-monolith profile first, and this ADR does
  not know which is genuinely first.
- The final `cfg.yaml` schema and validation tooling, and where
  `tb-extensions/manifest.yaml` should physically live and how it is
  versioned (Decision 8) -- a representative shape is given here, not a
  final one.
- Explicit confirmation of this ADR's final numbering once the
  M8-graduation branch (`docs/adr/0010-worm-s3-adapter-graduation.md`,
  currently unmerged) is either merged or dropped (see Numbering note).

## Non-goals (this ADR does not do)

- Does not write any Java or further Python platform code (per explicit
  instruction).
- Does not design the BIRT importer, the rule-chain conversion matrix,
  or the shadow-parity replay mechanism in detail (Risks 3-4) -- named
  as M9 prerequisites, not solved here.
- Does not pin a specific ThingsBoard CE version, a specific ArchUnit
  rule set, or a final `cfg.yaml`/manifest schema -- ties to Missing
  Inputs.
- Does not make a final monorepo-vs-separate-repos call where org
  constraints are unknown -- gives a recommendation with explicit
  conditions under which the alternative would be preferred.
- Does not change the status or content of `scripts/check.py` or any
  existing Python module; M0-M8's code and tests are untouched by this
  ADR.
