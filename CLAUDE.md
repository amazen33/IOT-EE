# CLAUDE.md

Development contract and architectural conventions for the IOT-EE platform.
All changes require PR, CI, and human review; an agent must not approve its own work.

Prompt frame: state the milestone, bounded context, verified inputs, acceptance criteria, security constraints, intended files, relevant tests, and non-goals. Distinguish assumptions from observations. Inspect before editing and preserve unrelated work.

Secrets and PII: never include raw PII, credentials, keys, production dumps, or vault secret values in prompts, Git, app databases, events, logs, or audit evidence. Use synthetic inputs. Enter source secrets once directly into the scaffold-configured vault; persist only references. Never automatically import secrets from legacy exports. No unrestricted cloud commands or cloud admin credentials in browser flows.

ThingsBoard preservation: preserve ThingsBoard rules through authorized export, inventory, shadow comparison, and approval. Screenshots describe visible UI only, never hidden formulas or rules. Enforce tenant authorization, versioned contracts, idempotent consumers, transactional outbox, and redacted evidence. Never claim atomicity across ThingsBoard and Kafka.

Constitutional engineering rules (see ADR 0011 Decision 4 for the full text): no secrets/PII/payment data/private keys/device credentials in source, Git, events, logs, metrics, prompts, or fixtures; server-issued correlation IDs preserved end-to-end; signed device-verified firmware (WebSockets report progress, not binaries); no destructive/infra/deploy/secret-rotation action or test-gate bypass without explicit human approval; no DR/compliance/migration completion claims from local tests alone; RBAC + ABAC + mTLS/JWT/SSO multi-tenancy.

## Milestones
### Python era (M0–M8) — reference implementation
M0 foundation

M1 Migration Studio

M2 domain core

M3 MQTT/PostgreSQL ingestion, Kafka, TB shadow parity

M4 APISIX/SSR/WebSockets/reports

M5 firmware

M6 immutable evidence / resilience / DR

M7 optional monetization

M8 Deployment Studio / multi-environment

The M0–M8 Python code is a specification / reference implementation, not the platform codebase. Per ADR 0011 Decision 9 and ADR 0012 Decision 8, it is frozen and relocated to spec/. No further Python platform code is written except the two Python-scoped areas named in ADR 0011 Decision 1 (the RAG subsystem and standalone adapters).

### Post-M8 platform era (ADR 0011–0013)
ADR 0011 pivoted the platform to Java 17 + Spring Boot / Spring Cloud, extending ThingsBoard CE as an upgradeable compatibility core. Python is scoped to the RAG subsystem and standalone adapters only.

ADR 0012 scoped M9 — Track A (TB CE investigation, a document) and Track B (Java bootstrap: Maven parent, common/ primitives, ArchUnit gates, services/identity walking skeleton) — and relocated the M0–M8 Python tree to spec/ (Decision 7).

ADR 0013 established microservice autonomy: services share only contracts (.proto, OpenAPI, JSON Schema) and test-scope build artifacts (ArchUnit rules). No shared runtime JARs. Per-service RBAC, correlation, and Spring wiring, duplicated by design — no duplication ceiling; repeated code is a boundary signal, never grounds to reopen a shared JAR.

M9 is in progress. Track A merged; Track B refactor against ADR 0013 pending.

Post-v1.0.0 work is scoped milestone-by-milestone. There is no M9+ roadmap yet — it is defined when the prior milestone closes.

## Repository layout

```
contracts/                  language-neutral contracts (.proto, OpenAPI, JSON Schema)
architecture/               ArchUnit rule factories, test scope only (build-time shared artifact)
services/<name>/            autonomous Java services
  └─ domain/                 framework-free domain model (no Spring, no JPA, no HTTP)
  └─ core/                   framework-free handler/use-case logic, calls domain/
  └─ rbac/                   framework-free authorization primitives (roles, permissions, ABAC)
  └─ correlation/            correlation-ID primitives + this service's own Spring adapters for them
  └─ web/                    thin Spring adapter delegating to core/
adapters/<provider>/        real-backend implementations behind contracts (M10+; none exist in Java yet)
  └─ (e.g. worm_s3)         built from spec/ patterns; isolated, optional deps
deploy/                     Ansible, Terraform/OpenTofu, Helm, Argo CD
spec/                       frozen M0–M8 Python reference implementation (not the platform)
docs/                       ADRs, architecture guides, inventories
  └─ adr/                   numbered ADRs (0001–present) + README.md convention
.github/workflows/          CI: python spec gate + java platform gate
```

docs/, CLAUDE.md, README.md, .github/workflows/ stay at the repository root — they describe the project as a whole, not any single language tree.

## Gates

Both gates run in CI on every change. Both must pass for changes touching their scope.

| Gate | Command | Scope |
| --- | --- | --- |
| Python spec gate | `python spec/scripts/check.py` | The frozen M0–M8 reference implementation only. Mechanical isolation checks + full regression suite. |
| Java platform gate | `mvn -f pom.xml verify` | contracts/, architecture/, services/*, adapters/*. Compile + unit tests + ArchUnit boundary rules. |

After every stage run its complete relevant gate and the full regression gate. A stage requires code, tests, docs, deployment artifacts, and passing gates; document unavailable external verification as blocked, not passed.

## Architectural principles
### Microservice autonomy (ADR 0013)
Zero shared runtime — services do not share executable Java libraries containing business logic, domain classes, repositories, Spring components, or helper implementations.

Contracts over libraries — communication relies on versioned schemas (Protobuf / OpenAPI / JSON Schema) rather than shared code dependencies. Each service generates its own Protobuf classes from .proto contracts as part of its own build.

Standard format libraries allowed — Jackson, protobuf-java, CloudEvents SDK, etc. are ordinary per-service third-party dependencies. Do not hand-roll a serializer to "purify" a service.

### Hexagonal architecture
Pure domain core — business rules live in a framework-free layer. No org.springframework.., jakarta.servlet.., jakarta.ws.rs.. imports in domain/, core/, or rbac/ (each has its own ArchUnit rule).

Database independence — the domain knows nothing about PostgreSQL, JPA, or SQL. Persistence via repository interfaces (ports); implemented by infrastructure adapters. jakarta.persistence.. annotations permitted; org.hibernate.., org.eclipse.persistence.., and org.jooq.. are denied by ArchUnit rule (no persistence code exists yet, so this rule is currently vacuously satisfied, not exercised against real persistence code).

Protocol independence — REST, gRPC, and Kafka are driving/driven adapters. Incoming requests translate to plain Java commands before hitting the domain; domain events translate to Kafka messages at the outer edge.

### Plug-and-play infrastructure
Every infrastructure component is an interchangeable plugin behind an abstract contract: TB CE (replaceable), Kafka (replaceable with Redpanda/RabbitMQ), CDC (Debezium, replaceable), APISIX (replaceable with NGINX/Envoy), Spring Boot (replaceable/upgradeable without touching business logic), storage (MinIO/S3/Azure/GCS behind a WORM port), payments (Stripe behind a payment-provider port).

Enforcement: Maven Enforcer bannedDependencies + ArchUnit autonomy rules, both in the build. Test-scope shared artifacts (ArchUnit rules, checkstyle configs, .proto, JSON Schema) are permitted; runtime shared artifacts with business logic are prohibited.

## ADR conventions
ADRs live in docs/adr/ and are numbered sequentially. The number is assigned at merge, not at branch creation.

Drafts use docs/adr/XXXX-proposed-<slug>.md

At merge, the file is renamed to docs/adr/<NNNN>-<slug>.md with the next free number

Superseding an ADR: write a new ADR that references and supersedes the old one; do not edit the old one

See docs/adr/README.md for the current convention and next free number

## Stage-completion checklist
A stage is not complete without all of:

□ Code implementing the stage's scope
□ Tests — unit + boundary (ArchUnit for Java, check.py for Python)
□ Documentation — architecture notes, ADR if the stage introduces a new decision
□ Deployment artifacts where the stage touches infrastructure (Ansible/Terraform/Helm/Argo CD)
□ Stage gate passes (mvn verify and/or python spec/scripts/check.py as applicable)
□ Full regression gate passes (both gates green)
□ Blocked items recorded explicitly — external verification not available is documented as blocked, not passed
Supply-chain security scanning (planned — ADR 0014, post-v1.0.0)
Trivy scans for CVEs in dependencies, container images, IaC misconfigurations, and secrets. SBOM generation and image signing (cosign/Sigstore) are part of the same ADR. Until ADR 0014 is ratified, scanning is not part of the CI gate — do not assume a green build means a clean bill of health.

M0–M8 legacy constraints (still binding)
The original M0 constraint remains in force as a standing rule:

The Python spec tree (spec/) must not provision infrastructure, import legacy data, or execute unrestricted cloud commands.

Migration Studio's data import is a separate concern governed by its own ADR (see docs/adr/0002), and is still pending real legacy system access.

Legacy ThingsBoard CE exports are handled through authorized export, inventory, shadow comparison, and approval — never recreated from memory or screenshots.

Reading order for a new session
If you are a new agent starting work on this repository, read in this order:

CLAUDE.md (this file) — conventions and current state

docs/adr/0011-platform-architecture-and-language-stack.md — the platform pivot

docs/adr/0012-m9-foundation-scope.md — the current milestone

docs/adr/0013-microservice-autonomy.md — the autonomy rule (renumbered at merge)

docs/adr/README.md — ADR numbering convention

The specific milestone/ADR relevant to your task

Do not start writing code until you have read the relevant ADR for the work you are about to do.

