# ADR XXXX (proposed) -- M9: foundation scope -- TB CE investigation (Track A) and Java bootstrap (Track B)

Status: proposed / draft, all four open implementation choices
(Decisions 3, 4, 6, 7) confirmed by the repository owner. Filed under
`docs/adr/README.md`'s numbering convention (a real number is assigned
at merge, not now); this file's final path is
`docs/adr/<NNNN>-m9-foundation-scope.md`. No code -- Java or Python --
is written against this ADR until the branch-base note below is also
resolved (rebase onto real `main`, confirmed and pushed), per the
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
  as `tests/test_check_script_gates.py`. `services/identity`'s own
  first commit is that walking-skeleton slice, using `common/` and
  passing the ArchUnit gates (Decision 6) -- not a separate throwaway
  module.

Explicitly **not** M9 (repository owner's own list, restated in
Non-goals below): any `TbNode`/`AbstractIntegration` implementation, any
business-logic Java service, any *final* `cfg.yaml` schema, any
Migration Studio or Deployment Studio code. Those are M10+.

Four concrete implementation choices Track B depends on were left open
by both ADR 0011 and the repository owner's scoping message; this ADR
proposed an answer for each, and **all four are now confirmed** by the
repository owner: build tool (Maven), the event/command envelope's
schema format (Protobuf), the walking-skeleton slice's placement
(`services/identity`'s own first commit, not a separate module), and
the `spec/` relocation (happens in M9, sequenced after the WORM-adapter
PR merges and before Track B begins). See Decisions 3, 4, 6, and 7. No
code is written against any of them until the branch-base note below
is also resolved.

## Branch base note

This ADR's own branch, `docs/m9-foundation-scope-adr`, is currently
built on `d8dee00` -- the local tip of `docs/platform-architecture-adr`
(ADR 0011) -- **not** on the real merged tip of `main`, `f97919f` (PR
#9). This session's shell cannot independently verify `main`'s true
state (`git fetch`/`git pull` from here is blocked by a proxy 403), so
this gap can only be closed from the repository owner's own terminal,
where GitHub access already works.

**Required before any Track A/B work begins:** rebase
`docs/m9-foundation-scope-adr` onto real `main` (which should already
contain `f97919f`, and may by then also contain the merged WORM-adapter
PR, ADR 0010 -- see Decision 7's sequencing), confirm the rebase is
clean, and push the rebased branch. Track A and Track B code/documents
are **not** started until that rebase is confirmed and the branch is
pushed -- this is now a hard gate on top of this ADR's own agreement,
not a formality.

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

### 3. Build tool: Maven (confirmed)

ADR 0011's skeleton names "Maven/Gradle parent" without choosing;
**Maven is confirmed** for the reason given when this was proposed: a
declarative, XML-based multi-module POM is more directly analogous to
`scripts/check.py`'s own style (explicit, verbose, easy to diff in
review) than Gradle's programmable build scripts, which matters most
right when the whole point of Track B is to prove a *mechanically
verifiable* build discipline exists.

### 4. Event/command envelope schema format: Protobuf (confirmed, platform-wide, no future Avro)

The repository owner rejected deferring this and settled the format,
not just the direction: `common/`'s event/command envelope is
**Protobuf, and only Protobuf, platform-wide.** Rationale, as stated by
the repository owner: ADR 0011 already mandates gRPC for inter-service
transport, and gRPC is Protobuf-native. Choosing Avro for Kafka instead
would mean maintaining two schema definitions per event (`.avsc` for
Kafka, `.proto` for gRPC), two schema registries, and two drift
surfaces -- directly working against ADR 0011's own constitutional rule
that event contracts are versioned, additive, and compatibility-tested.
Protobuf-only gives one source of truth serving both transports.

**Envelope shape.** CloudEvents v1.0 semantics, Protobuf binding.
Top-level fields: `id` (server-issued correlation ID -- see Decision
5), `source`, `type`, `specversion`, `time`, plus `causation_id`,
`tenant_id`, and `idempotency_key` as top-level extension fields.
`data` is a typed Protobuf message specific to each event type,
versioned independently of the envelope itself. The APISIX edge's JSON
binding uses the same logical envelope -- identical semantics,
serialized as JSON rather than binary Protobuf at that one boundary.

**`idempotency_key`.** The constitutional rule requires idempotency
data preserved end-to-end (ADR 0011 Decision 4), and `id`/`causation_id`
alone do not supply a dedup key -- `id` identifies *this* envelope
instance, not a stable identity that survives transport-level retries
or re-emission. `idempotency_key` fills that gap: for platform-
originated events, `idempotency_key == id` (the envelope's own identity
is already stable and unique at creation, so no second value is
needed). For re-emitted external events -- most concretely a TB CE-
originated event replayed into the platform -- `idempotency_key` is
derived from the source system's own unique identifier for that event
(e.g. TB CE's own event/message ID), not from a freshly generated `id`,
so that consumers dedup correctly regardless of how many times the
transport retries delivery or TB CE itself re-emits the same
underlying occurrence.

**Avro is not the platform envelope format**, and is not deferred as a
future option for the platform envelope. If a future Python consumer
(e.g. the RAG service) genuinely needs Avro for its own internal
storage, that is a decision scoped to that service alone, made behind
its own port/adapter boundary -- never a platform-wide envelope
decision. There is no open "Avro for later" question; this is settled.

### 5. Correlation-ID standard: a platform-level `X-Correlation-ID` header/field, distinct from distributed-tracing context

`common/`'s correlation-ID primitive is a single, explicit, server-
issued identifier (a UUID string) carried as `X-Correlation-ID` over
HTTP/gRPC metadata and as a Kafka message header -- not the message
payload -- continuing this repository's own M0-M8 precedent
(`foundation.contracts`' synthetic fixtures already carry a
`correlation_id` field). This is deliberately a narrower, simpler
concern than full distributed tracing (W3C Trace Context's
`traceparent`, which Micrometer Tracing handles at the observability
layer feeding Tempo, per ADR 0011's LGTM clarification) -- the two are
complementary: `X-Correlation-ID` is this platform's own causation/
idempotency identifier (a constitutional requirement, ADR 0011 Decision
4), while a trace ID is an observability concern.

**Origin.** The APISIX edge is where the correlation ID is issued for
externally-originated requests -- the natural choice, since it is
already the platform's single ingress point (ADR 0011 Decision 3). If
an inbound request already carries a client-supplied `X-Correlation-ID`,
APISIX **overrides** it with a freshly server-issued ID rather than
honoring the client's value: override is the safer default, since a
client-supplied ID cannot be trusted for uniqueness or format, and a
platform-level causation/idempotency identifier (Decision 4's
`idempotency_key` and `id` fields depend on it) must not inherit an
untrusted value. This is a stricter rule than distributed tracing
typically applies to trace IDs, and is deliberate for that reason. How
a TB CE-originated event (one that never passes through APISIX) gets
its correlation ID assigned remains open and is Track A's job to
resolve (Risk 2 below), not decided here.

### 6. `services/identity`'s first commit IS the walking skeleton (confirmed; no separate stub module)

The repository owner rejected a dedicated throwaway module on principle:
a walking skeleton is a technique (the thinnest possible slice of the
*real* system, proven end-to-end), not a bounded context, so it has no
business under `services/*` as its own placeholder name -- and a
separate probe module contradicts the technique itself, becoming dead
code the moment a real service exists. Confirmed instead:
`services/identity`'s first commit *is* the walking-skeleton slice --
a minimal Spring Boot main class, one `Tenant` object, one endpoint that
consults `common/`'s RBAC primitive, one ArchUnit rule, and one negative
ArchUnit test that plants a violation and asserts the build fails
(mirroring `tests/test_check_script_gates.py`'s discipline). Documented
explicitly, here and in `services/identity`'s own README once it
exists: **the first commit is the walking skeleton; subsequent commits
add real tenancy/RBAC/ABAC.** Explicitly out of scope for this first
slice: full RBAC/ABAC, SSO/JWT, persistence, and the full M2 permission
catalog -- those are `services/identity`'s own subsequent commits, not
M9's.

### 7. Repo topology: relocate `spec/` now, as its own commit, sequenced strictly after the WORM-adapter PR merges and strictly before Track B begins (confirmed)

The repository owner confirmed the `spec/` relocation happens in M9,
not later -- but with a specific ordering that is now load-bearing for
the rest of this ADR:

1. **The `feature/worm-s3-adapter-graduation` PR (ADR 0010) merges to
   `main` first.** Not optional, not reorderable: that branch adds a
   root-level `adapters/` package (`adapters/worm_s3`); ADR 0011's Java
   skeleton also wants a root-level `adapters/` (`adapters/{mqtt,apisix,
   storage}/`). Relocating `spec/` *before* the WORM PR merges would
   still leave a real name collision the moment that PR lands afterward;
   relocating *after* Track B has already started compounds the
   collision with every subsequent Java commit. The WORM PR's own
   review (already in progress, independent of this ADR) is the actual
   blocking gate here, not anything M9 controls.
2. **The `spec/` relocation happens next, as its own atomic,
   independently reviewable commit** -- not bundled with Track A or
   Track B. Scope of the move: every Python package, `scripts/`,
   `tests/`, `fixtures/`, `deployment/`, and `requirements*.txt` (which
   by then includes `requirements-adapters-s3.txt`) move under `spec/`
   via `git mv` (history-preserving). `docs/`, `CLAUDE.md`, `README.md`,
   and `.github/workflows/` stay at the repository root -- they are
   shared between the Python spec and the Java platform, not
   Python-specific. `scripts/check.py`'s `ROOT = Path(__file__).resolve
   ().parents[1]` is relative to the script's own location and
   self-corrects once the file lives at `spec/scripts/check.py`; only
   the two or three required-artifact entries in it that point at
   `docs/...` need a `ROOT.parent / "docs/..."`-style adjustment (since
   `docs/` no longer lives under the new `ROOT`). CI is updated to
   invoke `python spec/scripts/check.py`. A new `spec/README.md` states
   plainly that this tree is the reference implementation, not the
   platform (continuing ADR 0011 Decision 9's framing). Verification
   before this commit is considered done: `python spec/scripts/check.py`
   still reports 407/407 (or whatever count the WORM merge brings it
   to); `git log --follow` traces each moved file's history across the
   move; `git status` shows renames, not delete-plus-add pairs.
3. **Track B begins only after step 2 completes** -- this is what makes
   Track B's own root-level `adapters/` (a real ADR 0011 skeleton
   directory) safe to create: the name is free because the Python one
   has already moved to `spec/adapters/`.

Track A has no such dependency (it produces a document, touching no
code paths) and may proceed in parallel with, or ahead of, all three
steps above.

### 8. Java-only; Python is content-untouched in M9

Neither track writes or modifies Python code. Track A is a document.
Track B is Java. Python is **content-untouched**, not
location-untouched: Decision 7's `spec/` relocation moves every Python
file's *path* (via `git mv`), but not its contents -- `scripts/check.py`
continues running and passing at the same test count, on the same
source, exactly as ADR 0011 already established, whether invoked as
`python scripts/check.py` (before the move) or
`python spec/scripts/check.py` (after it).

## Consequences

- **Easier**: M10+ business-logic work (any real `services/*`
  implementation, the first real TB CE extension, Migration Studio's
  importer) starts on a build, dependency-injection, and mechanical-gate
  foundation that has already been proven to work, rather than being
  designed for the first time alongside real business logic.
- **Harder / new work**: four concrete technical choices (build tool,
  envelope schema, skeleton-service naming, repo topology) needed
  answers this ADR could not source from ADR 0011 or the repository
  owner's scoping message alone -- all four are now confirmed (Decisions
  3, 4, 6, 7).
- **To revisit**: if a future Python consumer (e.g. RAG) needs Avro for
  its own internal storage, that is scoped to that service behind its
  own port -- never reopens the platform-wide Protobuf decision
  (Decision 4). Nothing about `services/identity`'s walking-skeleton
  slice needs revisiting or deleting once a first real feature lands --
  by design it *is* the first real service, just its thinnest possible
  commit (Decision 6).

## Risks

1. **Track A and Track B are only loosely coupled in M9, by design --
   but that means Track B's correlation-ID and envelope decisions
   (Decisions 4-5) are made without yet knowing whether TB CE's actual
   extension hooks can carry them cleanly.** If Track A's investigation
   later finds TB CE cannot easily propagate a custom header through a
   given hook, `common/`'s correlation-ID primitive may need a second,
   TB-CE-specific carrier mechanism in M10 -- flagged now so it isn't a
   surprise later.
2. **One ArchUnit rule set tested against exactly one thin slice
   (`services/identity`'s walking-skeleton commit) may not exercise
   real boundary violations -- and the specific rule ADR 0011 Decision
   3 names (no `services/*` module depends on a vendor SDK directly
   outside `adapters/*`) is vacuously true in M9, because no
   `adapters/*` module exists yet for anything to depend on.** The
   negative test (plant a violation, assert the build fails) proves
   the *rule fires* on a synthetic violation; it does not prove the
   *boundary is populated* or that a real service with a real vendor
   dependency will actually be routed through `adapters/*` once M10+
   creates the first one. Track B closes this gap either by stating it
   plainly as an M9 limitation (the rule is proven, the boundary is not
   yet exercised) or by adding a minimal `adapters/.gitkeep` in M9 so
   the boundary's shape exists from day one, even with nothing real
   behind it -- the choice between the two is Track B's own
   implementation detail, not re-litigated here.
3. **Maven vs. Gradle was a costly-to-reverse choice once M10+ services
   accumulate.** Maven is now confirmed (Decision 3), which closes this
   risk rather than merely flagging it -- noted here because it was the
   one Decision 3-7 choice this ADR called out as expensive to undo,
   and is now locked in before any module exists to make reversal even
   more expensive.
4. **A "proposed" ADR number risks drifting from `docs/adr/README.md`'s
   own convention if this branch and another draft both merge out of
   order.** Mitigated by following that convention here: this file's
   name carries no number yet, and gets one only at merge.
5. **This branch is not currently based on the real, merged `main`.**
   See the branch-base note below -- this is a blocking risk, not a
   cosmetic one: code written against a stale base would need to be
   rebased anyway, and the `spec/`-relocation sequencing in Decision 7
   depends on knowing the true state of `main` (specifically, whether
   the WORM-adapter PR has actually merged there yet).

## Missing inputs

- Confirmation that `docs/m9-foundation-scope-adr` has been rebased
  onto the real merged `main` and pushed (see Branch base note above)
  -- blocking for Track A/B, independent of this ADR's own agreement.
- Whether the repository owner wants `docs/tb-ce-inventory.md` (Track
  A's output) reviewed and merged as its own PR before or alongside
  Track B's code, given they are otherwise independent.
- Confirmation that the `feature/worm-s3-adapter-graduation` PR (ADR
  0010) has merged to `main`, since Decision 7's `spec/`-relocation
  step is sequenced strictly after that merge.

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
- Any Avro schema for the platform event/command envelope, now or
  later (Decision 4) -- Avro, if it appears at all, is scoped to a
  single Python consumer's internal storage, never the platform
  envelope.

## Acceptance criteria

**Track A (TB CE investigation)**

| Criterion | Done when |
| --- | --- |
| TB CE version pinned | `docs/tb-ce-inventory.md` names a specific TB CE release/edition |
| Extension API surface inventoried | The document lists `TbNode`/`@RuleNode`/`AbstractIntegration` availability and any rule-chain/event hooks, at the pinned version, with citations (TB CE's own docs/source) |
| Draft manifest produced | A first `tb-extensions/manifest.yaml` exists, matching ADR 0011 Decision 8's intent, even if incomplete |
| Representative event captured | One serialized example event is included, confirmed synthetic or fully redacted (Decision 2) -- no raw production data |
| No code written | Track A's PR contains no Java or Python source files |

**Prerequisite gate (blocking on both tracks starting code/document work)**

| Criterion | Done when |
| --- | --- |
| Branch rebased onto real `main` | `docs/m9-foundation-scope-adr` is rebased onto `main` (containing at least `f97919f`), the rebase is clean, and the branch is pushed -- see Branch base note |

**`spec/` relocation (its own commit, gates Track B only -- Decision 7)**

| Criterion | Done when |
| --- | --- |
| WORM-adapter PR merged | `feature/worm-s3-adapter-graduation` (ADR 0010) is merged to `main` |
| Python tree relocated | All Python packages, `scripts/`, `tests/`, `fixtures/`, `deployment/`, and `requirements*.txt` moved under `spec/` via `git mv`, as one atomic commit, separate from Track A or Track B |
| Gate still green post-move | `python spec/scripts/check.py` passes at the same count the WORM merge left it at; `git log --follow` traces moved-file history; `git status` shows renames, not delete-plus-add |
| CI and docs updated | CI invokes `python spec/scripts/check.py`; `spec/README.md` states the tree is the reference implementation, not the platform |

**Track B (Java bootstrap)**

| Criterion | Done when |
| --- | --- |
| Multi-module build stands up | The Maven parent build (Decision 3) builds successfully with `common/` and `services/identity` as modules |
| `common/` implements the four primitives | Event/command envelope (Protobuf, CloudEvents v1.0 semantics, Decision 4), correlation-ID context (Decision 5), a draft `cfg.yaml` JSON Schema, and RBAC/ABAC primitive types all exist, are code-generated where applicable, and are unit-tested |
| ArchUnit base rules exist and are proven | At least one rule per env-agnostic boundary named in ADR 0011 Decision 3 (e.g. no `services/*` module depends on a vendor SDK directly outside `adapters/*`); each rule has a negative test that plants a violation and asserts the build fails, mirroring `tests/test_check_script_gates.py` |
| `services/identity`'s walking-skeleton commit passes the gate | `services/identity` (Decision 6) builds, starts, serves a health endpoint backed by `common/`'s RBAC primitive, and passes every ArchUnit rule -- documented in its own README as the walking-skeleton slice |
| Full build is green | The complete Track B build (all modules, all tests, all ArchUnit rules) passes in one command, analogous to `python scripts/check.py`'s single-command regression gate |

