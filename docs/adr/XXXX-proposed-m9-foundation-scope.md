# ADR XXXX (proposed) -- M9: foundation scope -- TB CE investigation (Track A) and Java bootstrap (Track B)

Status: proposed / draft. Filed under `docs/adr/README.md`'s numbering
convention (a real number is assigned at merge, not now); this file's
final path is `docs/adr/<NNNN>-m9-foundation-scope.md`. No code -- Java
or Python -- is written against this ADR until it is agreed, per the
repository owner's standing instruction and ADR 0011's own gate.

## Context

ADR 0011 is merged; per its own text, M9 may now be scoped. ADR 0011
named several candidate M9 prerequisites (final `cfg.yaml` schema, the
`tb-extensions/manifest.yaml` format, the Java skeleton bootstrap,
ArchUnit rule design, correlation-ID propagation, the BIRT/rule-chain
compatibility matrix, the shadow-parity replay mechanism) without
committing all of them to one milestone. The repository owner narrowed
M9 explicitly, in two tracks, both foundational and neither touching
business logic:

- **Track A -- TB CE investigation (no code, a document).** Pin the
  actual TB CE version (ADR 0011 Risk 7). Inventory its extension API
  surface at that version (`TbNode`, `@RuleNode`, `AbstractIntegration`,
  rule-chain hooks, event hooks). Draft a first
  `tb-extensions/manifest.yaml` (ADR 0011 Decision 8's upgrade
  contract). Capture one representative TB CE event, serialized, so
  downstream Java services can be designed against real data shape.
- **Track B -- Java bootstrap (a thin, real slice).** Stand up the
  build-tool multi-module parent from ADR 0011's skeleton. Build
  `common/` with the event/command envelope, a correlation-ID standard,
  a draft `cfg.yaml` JSON Schema, and RBAC/ABAC primitives. Build the
  ArchUnit base rule set -- one rule per boundary, mirroring
  `scripts/check.py`'s discipline -- with a negative test per rule that
  plants a violation and asserts the build fails, the same discipline
  as `tests/test_check_script_gates.py`. Build one trivial
  "walking-skeleton" service that uses `common/` and passes the
  ArchUnit gates.

Explicitly **not** M9 (repository owner's own list, restated in
Non-goals below): any `TbNode`/`AbstractIntegration` implementation, any
business-logic Java service, any *final* `cfg.yaml` schema, any
Migration Studio or Deployment Studio code. Those are M10+.

Four concrete implementation choices Track B depends on were left open
by both ADR 0011 and the repository owner's scoping message; this ADR
proposes an answer for each rather than leaving `common/` design
underspecified, and asks for confirmation via this turn's scoping
questions (see the end of this message) before any code is written:
build tool (Maven vs. Gradle), the event/command envelope's schema
format, the walking-skeleton service's name/placement, and whether the
`spec/` relocation happens in M9 or is deferred.

## Decisions

### 1. M9 scope is exactly Track A + Track B

No TB CE extension code, no business-logic service, no final
`cfg.yaml`, no Migration/Deployment Studio work. A milestone this
narrow is a deliberate choice: M9 exists to prove the two riskiest
unknowns ADR 0011 flagged (TB CE's real extension surface, and whether
the ArchUnit-based mechanical-gate discipline actually works in Java)
before any bounded-context business logic is built on top of either.

### 2. Track A output is a document, and any captured example data is synthetic or redacted -- never a raw production export

Track A's deliverable is `docs/tb-ce-inventory.md` (or equivalent): the
pinned TB CE version, its extension-point inventory, a draft
`tb-extensions/manifest.yaml`, and one representative event shape. This
last item interacts directly with this repository's constitutional
rule (carried into ADR 0011 unchanged): no raw PII, credentials, or
production exports in source, Git, logs, or docs. **The captured
representative TB CE event must be synthetic or fully redacted before
it is committed** -- either a genuinely synthetic event constructed to
match the real shape, or a real event with every field that could carry
tenant/device/customer-identifying data replaced, exactly as this
repo's own `fixtures/*.synthetic.json` files have done in every prior
milestone. This is not a new rule; it is the existing rule, restated
here because Track A is the first time this repository captures
anything from a *real* external system rather than authoring synthetic
data from scratch.

### 3. Build tool: Maven (proposed -- see scoping question 1)

ADR 0011's skeleton names "Maven/Gradle parent" without choosing. This
ADR proposes **Maven**, for one reason specific to M9's own goal: a
declarative, XML-based multi-module POM is more directly analogous to
`scripts/check.py`'s own style (explicit, verbose, easy to diff in
review) than Gradle's programmable build scripts, which matters most
right when the whole point of Track B is to prove a *mechanically
verifiable* build discipline exists. Gradle (Kotlin DSL) is a
reasonable alternative if the repository owner has a standing
preference; this is exactly the kind of small, foundational,
hard-to-reverse-cheaply choice this ADR surfaces as a scoping question
rather than deciding unilaterally.

### 4. Event/command envelope schema format: JSON Schema (proposed -- see scoping question 2)

`common/`'s event/command envelope needs a concrete schema technology,
not just "versioned, additive" as a principle. This ADR proposes **JSON
Schema** for M9, deferring an Avro/Protobuf-plus-schema-registry
decision to whichever milestone first needs real Kafka producer/
consumer code (M10's `services/ingestion` bridge, per ADR 0011's
mapping table) -- JSON Schema is human-readable, needs no registry
infrastructure, and is what this repository's own `cfg.yaml` validation
already uses (ADR 0011 Decision 8), keeping M9's foundation internally
consistent. This is explicitly a placeholder-for-now decision: M9's
walking-skeleton service does not touch Kafka at all, so nothing here
is locked in for the ingestion bridge's eventual real choice.

### 5. Correlation-ID standard: a platform-level `X-Correlation-Id` header/field, distinct from distributed-tracing context

`common/`'s correlation-ID primitive is a single, explicit, server-
issued identifier (a UUID string) carried as `X-Correlation-Id` over
HTTP/gRPC metadata and as a Kafka message header -- not the message
payload -- continuing this repository's own M0-M8 precedent
(`foundation.contracts`' synthetic fixtures already carry a
`correlation_id` field). This is deliberately a narrower, simpler
concern than full distributed tracing (W3C Trace Context's
`traceparent`, which Micrometer Tracing handles at the observability
layer feeding Tempo, per ADR 0011's LGTM clarification) -- the two are
complementary: `X-Correlation-Id` is this platform's own causation/
idempotency identifier (a constitutional requirement, ADR 0011 Decision
4), while a trace ID is an observability concern. Confirming this
interoperates with TB CE's own request/event lifecycle is Track A's
job (Risk 2 below), not decided here.

### 6. Walking-skeleton service: `services/walking-skeleton` (proposed -- see scoping question 3), not a real bounded-context stub

Track B's "one trivial service" is a genuinely empty Spring Boot
application (a health endpoint, nothing else) whose only purpose is
proving the multi-module build, `common/` dependency wiring, ArchUnit
gate, and container image all work end-to-end. This ADR proposes
placing it at a dedicated, obviously-non-functional path,
`services/walking-skeleton/`, rather than `services/identity/` (a name
from ADR 0011's real skeleton) -- using a real bounded context's name
for an empty shell risks a future reader mistaking "the skeleton
compiles" for "identity is built," and the M9 gate (Acceptance
criteria, below) should retire this path once a real first service
exists, rather than accumulate stub logic inside a name that's supposed
to mean something.

### 7. Repo topology: bootstrap alongside the existing Python tree; do not relocate `spec/` in M9 (proposed -- see scoping question 4)

ADR 0011 recommended the polyglot-monorepo `spec/` relocation but
flagged it as blocked on org constraints not yet confirmed (Missing
Inputs). Absent that confirmation, this ADR proposes M9 adds the new
Java-related top-level directories (`common/`, `services/`,
`tb-extensions/`, `deploy/`, `cfg/`) alongside the existing Python tree
*without moving it* -- non-destructive, and reversible without a
history-rewriting relocation if the repo-topology question resolves
differently later. `scripts/check.py` and the existing Python tree are
untouched either way.

### 8. Java-only; Python is untouched in M9

Neither track touches Python code. Track A is a document. Track B is
Java. `scripts/check.py` continues running and passing on the existing
Python tree, unmodified in content, exactly as ADR 0011 already
established.

## Consequences

- **Easier**: M10+ business-logic work (any real `services/*`
  implementation, the first real TB CE extension, Migration Studio's
  importer) starts on a build, dependency-injection, and mechanical-gate
  foundation that has already been proven to work, rather than being
  designed for the first time alongside real business logic.
- **Harder / new work**: four concrete technical choices (build tool,
  envelope schema, skeleton-service naming, repo topology) needed
  answers this ADR could not source from ADR 0011 or the repository
  owner's scoping message alone -- proposed here, pending confirmation.
- **To revisit**: the event-envelope format once real Kafka producer/
  consumer code exists (Decision 4); whether `services/walking-
  skeleton` should be deleted or converted once a first real service
  lands (Decision 6); the `spec/` relocation once repo-topology org
  constraints are confirmed (Decision 7).

## Risks

1. **Track A and Track B are only loosely coupled in M9, by design --
   but that means Track B's correlation-ID and envelope decisions
   (Decisions 4-5) are made without yet knowing whether TB CE's actual
   extension hooks can carry them cleanly.** If Track A's investigation
   later finds TB CE cannot easily propagate a custom header through a
   given hook, `common/`'s correlation-ID primitive may need a second,
   TB-CE-specific carrier mechanism in M10 -- flagged now so it isn't a
   surprise later.
2. **One ArchUnit rule set tested against exactly one trivial service
   may not exercise real boundary violations.** A rule that "passes"
   only because nothing in the walking-skeleton service could violate
   it yet is a false confidence signal. Mitigated by Track B's own
   negative-test requirement (plant a violation, assert the build
   fails) -- but this only proves the *rule* works, not that the
   *boundary* will hold once real services with real temptations to
   cross it exist.
3. **Maven vs. Gradle is a costly-to-reverse choice once M10+ services
   accumulate.** This ADR proposes Maven (Decision 3) but flags it as a
   scoping question specifically because getting this wrong is expensive
   to undo later, unlike most of M9's other choices.
4. **A "proposed" ADR number risks drifting from `docs/adr/README.md`'s
   own convention if this branch and another draft both merge out of
   order.** Mitigated by following that convention here: this file's
   name carries no number yet, and gets one only at merge.

## Missing inputs

- Confirmation of the four proposed decisions (3, 4, 6, 7) -- this
  turn's scoping questions ask for exactly these.
- The actual TB CE version to pin (Track A's own first task, not
  something this ADR can supply).
- Whether the repository owner wants `docs/tb-ce-inventory.md` (Track
  A's output) reviewed and merged as its own PR before or alongside
  Track B's code, given they are otherwise independent.

## Non-goals (explicitly out of scope for M9)

- Any `TbNode`/`@RuleNode` or `AbstractIntegration` implementation --
  Track A only inventories the extension surface; it implements
  nothing against it.
- Any business-logic Java service (`services/identity`,
  `services/device`, `services/evidence`, etc., as real, working code).
- A *final* `cfg.yaml` schema -- Track B's schema is explicitly a draft
  covering `common/`'s own needs, not the full surface ADR 0011
  sketched.
- Any Migration Studio or Deployment Studio code.
- Any infrastructure provisioning, deployment, or cloud/cluster action.
- Relocating `spec/` (Decision 7, pending confirmation).

## Acceptance criteria

**Track A (TB CE investigation)**

| Criterion | Done when |
| --- | --- |
| TB CE version pinned | `docs/tb-ce-inventory.md` names a specific TB CE release/edition |
| Extension API surface inventoried | The document lists `TbNode`/`@RuleNode`/`AbstractIntegration` availability and any rule-chain/event hooks, at the pinned version, with citations (TB CE's own docs/source) |
| Draft manifest produced | A first `tb-extensions/manifest.yaml` exists, matching ADR 0011 Decision 8's intent, even if incomplete |
| Representative event captured | One serialized example event is included, confirmed synthetic or fully redacted (Decision 2) -- no raw production data |
| No code written | Track A's PR contains no Java or Python source files |

**Track B (Java bootstrap)**

| Criterion | Done when |
| --- | --- |
| Multi-module build stands up | The parent build (Maven, pending Decision 3's confirmation) builds successfully with `common/` and the walking-skeleton service as modules |
| `common/` implements the four primitives | Event/command envelope (JSON Schema-backed, Decision 4), correlation-ID context (Decision 5), a draft `cfg.yaml` JSON Schema, and RBAC/ABAC primitive types all exist and are unit-tested |
| ArchUnit base rules exist and are proven | At least one rule per env-agnostic boundary named in ADR 0011 Decision 3 (e.g. no `services/*` module depends on a vendor SDK directly outside `adapters/*`); each rule has a negative test that plants a violation and asserts the build fails, mirroring `tests/test_check_script_gates.py` |
| Walking-skeleton service passes the gate | `services/walking-skeleton` (Decision 6) builds, starts, serves a health endpoint, and passes every ArchUnit rule |
| Full build is green | The complete Track B build (all modules, all tests, all ArchUnit rules) passes in one command, analogous to `python scripts/check.py`'s single-command regression gate |
