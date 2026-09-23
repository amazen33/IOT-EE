# ADR 0013 (proposed) -- Microservice autonomy and contract-based sharing

Status: proposed -- findings endorsed and Path A (Decision 3) accepted
by the repository owner; five of this document's original open
questions are resolved into Decisions 6-9, below; pending only this
file's own PR merge to `main` for formal ADR-number assignment per
`docs/adr/README.md`'s numbering convention. This file's final path is
`docs/adr/<NNNN>-microservice-autonomy.md`. Per the repository owner's
explicit sequencing: Track B is not refactored until this ADR merges
to `main` -- the refactor is done against a ratified rule, not a
proposed one.

## Numbering note

Following `docs/adr/README.md`'s convention, this file is filed as
`docs/adr/XXXX-proposed-microservice-autonomy.md` and numbered at merge.
0013 is the next free slot as of this writing (0001-0012 are all
assigned on `main` or already in flight); if another draft merges first,
this one renumbers at its own merge, per the existing convention.

## Context

The repository owner clarified, precisely and bindingly, what "no shared
runtime" means for this platform's Java services:

> Services do not share executable Java libraries containing business
> logic, domain classes, repositories, Spring components, or helper
> implementations. Services may share interface contracts such as
> `.proto` files. Each service generates its own Protobuf classes from
> the contract as part of its own build. The generated classes are
> implementation artifacts of that service, not a shared runtime
> dependency.
>
> Shared: contract artifacts only -- `.proto`, OpenAPI, JSON Schema,
> architecture rules (build-time). Not shared: `common.jar` with
> `Device.java`, `TenantContext.java`, `SomeSharedHelper.java` that all
> services depend on.

This is a real clarification, not a restatement. ADR 0011 and ADR 0012
-- both still `Status: proposed`, and both the direct authority Track B
was built against -- describe the opposite shape in places. ADR 0011's
M0-M8-to-Java mapping table says the M0 foundation work becomes "a
shared `common` library's event/command envelope validation ... **used
by every service**." ADR 0011's proposed skeleton calls `common/` "a
shared test-fixture module every service's build depends on." ADR 0012
Decision 6 explicitly instructs `services/identity`'s walking skeleton
to consult "`common/`'s RBAC primitive." Track B was built faithfully
against that text. This audit's honest finding is that the text itself,
not just Track B's execution of it, needs to change -- which is exactly
what this document (as the seed of ADR 0013) is for: it supersedes the
"shared common library used by every service" framing in ADR 0011's
M0-M8 mapping table and repo-structure notes, and ADR 0012 Decision 6's
"consults `common/`'s RBAC primitive" instruction, with the rule quoted
above. Per the constraint on this audit, ADR 0011 and ADR 0012's files
are not rewritten; ADR 0013, once merged, is the documented supersession.

Scope of this audit, as commissioned: ADRs 0001-0012, the M0-M8 Python
spec (`spec/`, as reference only), `docs/tb-ce-inventory.md` and
`tb-extensions/manifest.yaml` (Track A), and everything on
`feature/track-b-java-bootstrap` (Track B) as of commit `3c39091`.

## 1. Aligned items -- what already matches the rule and should stay

| Item | Why it's aligned |
| --- | --- |
| `common/src/main/proto/.../envelope.proto` | A pure contract artifact: a `.proto` file with no logic, exactly what the rule names as shareable. |
| `common/src/main/resources/schema/cfg.schema.json` | A pure JSON Schema contract artifact, explicitly named as shareable. |
| `common/src/test/.../architecture/*ArchitectureRulesTest.java` and the `services/identity` equivalent | ArchUnit rules are "architecture rules (build-time)" -- the rule's fourth named shareable category. They run only at build/test time, are never on a service's runtime classpath, and enforce boundaries rather than implement behavior. |
| Track A (`docs/tb-ce-inventory.md`, `tb-extensions/manifest.yaml`) | No code at all (ADR 0012 Decision 2/Non-goals, verified: Track A's own PR contains no Java or Python source). The manifest explicitly states any TB CE-to-platform bridge "happens at an explicit boundary service, never by importing TB CE's internal `.proto` definitions" -- the manifest itself already states the contract-not-runtime discipline this ADR is generalizing. |
| `services/identity/domain/{Tenant,TenantId}.java` | Zero dependency on `common/`, on Spring, or on any other module. Pure, owned, framework-free domain code -- exactly what a bounded context's own domain layer should look like. |
| `services/identity/core/TenantPermissionsHandler`'s framework-freedom *with respect to Spring* | It has no Spring/servlet import and is unit-testable with bare JUnit (`TenantPermissionsHandlerTest`, no Spring context). The REST-independence correction from the prior session was implemented correctly on its own terms -- it just didn't also address runtime-sharing autonomy, a different axis. |
| The `core/` vs `web/` split in `services/identity` | A good structural pattern independent of this audit's findings -- keep it after the refactor; it is what makes the domain/handler layer easy to keep dependency-clean once `common/`'s RBAC dependency is removed. |
| The ArchUnit negative-test-fixture discipline (a real planted violation per rule, asserting the specific forbidden package appears in the failure report) | Mechanically proves each rule fires, not just that it's syntactically present -- this discipline should be extended to enforce the new autonomy rule (see Decision 5), not replaced. |
| `docs/adr/README.md`'s numbering convention | Directly enables this document's own filing; no issue. |

## 2. Gaps

Severity key: **blocker** = must not merge to `main` in this shape;
**should-fix** = real misalignment, workable to defer one cycle if
tracked explicitly; **acceptable-for-now** = technically imperfect but
low-risk at current scope (one service, M9 only).

| # | Gap | File/line | Severity | Why |
| --- | --- | --- | --- | --- |
| G1 | `common/` packages RBAC business logic (`RbacRegistry` -- an in-memory repository; `Role`, `Permission` -- domain enums with behavior; `AbacContext`/`AbacDecision` -- a decision-making interface and its stub) into one shared jar (`iotee-common`) that every service depends on. | `common/src/main/java/com/iotee/platform/common/rbac/{RbacRegistry,Role,Permission,AbacContext,AbacDecision}.java`; declared shareable via `common/pom.xml` lines 14-15 (`<artifactId>iotee-common</artifactId>`, `packaging>jar`) | **Blocker** | This is the rule's own counter-example almost verbatim: a repository (`RbacRegistry`), domain classes (`Role`, `Permission`), and a helper implementation (`AbacContext.alwaysPermit()`) shipped as `common.jar`, not a contract. |
| G2 | `common/` packages `CorrelationIdContext` -- a `ThreadLocal`-backed context holder with embedded policy logic (`adoptOrOrigin`'s trust-boundary decision) -- as a shared helper every service depends on. | `common/src/main/java/com/iotee/platform/common/correlation/CorrelationIdContext.java` (full file, esp. `adoptOrOrigin`, lines ~59-71) | **Blocker** | Framework-freedom (no Spring/servlet import) is a different axis from runtime-sharing autonomy. This class is exactly a "helper implementation" in the rule's prohibited sense, regardless of which frameworks it avoids. |
| G3 | `adapters/web-spring/` is a shared runtime library of Spring components (`CorrelationIdHandlerInterceptor`, `CorrelationIdServletFilter`, `WebSpringAutoConfiguration`) that any `services/*` module pulls in as an ordinary Maven dependency to get wiring "for free." | `adapters/web-spring/pom.xml` description (lines 17-35); `adapters/web-spring/src/main/java/.../WebSpringAutoConfiguration.java` | **Blocker** | The rule names "Spring components" explicitly as not shareable. This module is not a vendor-SDK adapter in the sense `adapters/worm_s3` (Python) or the ADR 0011 skeleton's `adapters/{mqtt,apisix,storage}` are (one real backend behind a contract, consumed by whichever one service needs that backend) -- it is Spring MVC wiring meant to be depended on by *every* service, which is the shared-runtime pattern itself, not an adapter to one. |
| G4 | `services/identity` depends on both `iotee-common` (for RBAC, via G1) and `iotee-adapter-web-spring` (via G3), and its own `core/TenantPermissionsHandler` directly instantiates `new RbacRegistry()` and imports `Permission`/`Role`/`AbacContext`/`AbacDecision`. | `services/identity/pom.xml` lines 24-33; `services/identity/src/main/java/com/iotee/platform/identity/core/TenantPermissionsHandler.java` lines 3-7, 29-30 | **Blocker** (consequence of G1-G3) | This is the concrete instance of G1-G3 actually executing: the walking skeleton's one piece of "real" logic is real specifically because it calls into a shared runtime dependency. Fixing G1-G3 without touching this file leaves the violation live. |
| G5 | The Protobuf envelope's generated Java classes are compiled once in `common/` and distributed as part of the same shared jar as G1/G2's business logic, rather than each service generating its own classes from the `.proto` contract as part of its own build. | `common/pom.xml` lines 38-41 (protobuf-java dependency), lines 58-71 (protobuf-maven-plugin bound to `common/`'s own build) | **Should-fix** | The `.proto` *source* is a legitimate contract artifact (aligned, see Section 1) -- the problem is purely the packaging/build model: right now a service cannot depend on "the envelope contract" without also depending on `common.jar`'s RBAC and correlation code, because they are the same artifact. Separating the contract source location from the generated-code-sharing model resolves this without touching the schema itself. |
| G6 | `common/`'s own module Javadoc and `services/identity`'s module Javadoc both describe the now-superseded shape as the intended design ("Every services/* module and every adapters/* module may depend on this module"; "the first commit that proves ... common/'s RBAC primitive ... work together end to end"), and ADR 0011/ADR 0012's text (not to be rewritten per this audit's constraints) still states the shared-library framing as the standing decision. | `common/pom.xml` lines 17-35 (module `<description>`); `services/identity/pom.xml` lines 17-23; ADR 0011 M0-M8 mapping table, "M0 Foundation" row; ADR 0011 "Repo structure proposal" notes; ADR 0012 Decision 6 | **Should-fix** | Documentation debt, not code debt -- but real: a future reader (or a future agent) taking ADR 0011/0012 at face value will rebuild exactly this shape again. ADR 0013, once merged, must be the explicit, citable supersession (see Decision 4). |
| G7 | No mechanical enforcement exists yet for the autonomy rule itself -- the current ArchUnit suite proves framework-freedom (no Spring/servlet/persistence-provider leakage) and sibling-context isolation (`services/identity` vs `services/billing` etc.), but nothing today would fail a build if a *new* shared business-logic module appeared and a service depended on it. | `common/src/test/.../CommonArchitectureRulesTest.java`, `CommonFrameworkFreedomArchitectureRulesTest.java`; `services/identity/src/test/.../IdentityArchitectureRulesTest.java` -- none of the three check for this | **Should-fix** | Consistent with this repository's own stated discipline (`scripts/check.py`'s mechanical-gate philosophy, carried into ADR 0011 Decision on ArchUnit): a rule that is only true by convention today will not stay true once M10+ adds a second and third service under time pressure. |
| G8 | TB CE / Track A leakage into services: **none found.** | `tb-extensions/manifest.yaml` (full file); `docs/tb-ce-inventory.md` | **Acceptable-for-now / not a gap** | No `services/*` code touches TB CE yet (ingestion is M10+ per ADR 0012 Non-goals). The manifest's own text already states the correct discipline for when that code is written. Flagged here only so the audit record shows this axis was checked, not skipped. |
| G9 | Swappability of TB CE for a different engine "without touching services": moot at current scope, and only partially a design goal at all. | ADR 0011 Decision 2 (TB CE is "upgradeable... not 'replaceable'") | **Acceptable-for-now** | ADR 0011 itself never commits to engine-swappability, only upgrade-with-minimal-friction against TB CE specifically. No Track B code depends on TB CE today, so there is nothing to regress. This becomes a real question only once `services/ingestion` (M10+) is built against the manifest's already-stated "explicit boundary service" discipline -- worth re-checking at that point, not now. |

## 3. Recommended path for Track B

**Recommendation: (A) -- refactor now, before merge.** This agrees with
the repository owner's stated prior, and the audit's own findings
reinforce rather than merely accept it.

| Path | What it costs | What it risks |
| --- | --- | --- |
| **(A) Refactor now** | One more focused commit (or two, per the existing split-PR precedent) on `feature/track-b-java-bootstrap`, before it merges: move `.proto`/JSON Schema sources to a `contracts/` root, give each service its own protobuf-maven-plugin generation step, delete `iotee-common`'s RBAC classes and `adapters/web-spring` as shared dependencies, replace them with per-service local code, add the ArchUnit/Enforcer rule from Decision 5. Estimated scope matches the repository owner's own estimate: 3 modules, ~47 files, most of it move/delete rather than new logic (RBAC and correlation logic together are under 250 lines total; moving them into `services/identity` verbatim, then deleting the shared copies, is mechanical). No CI has gone green on this branch yet (the MDC/logback fix from the immediately preceding stage is committed but unverified in CI), so there is no "already-shipped" state to disturb. | Low. The only real risk is doing the mechanical move incorrectly (import paths, ArchUnit package scopes) -- caught by the same mechanical verification (brace/paren balance, package/dir consistency, the Python regression gate) already used throughout Track B, plus a fresh CI run. |
| **(B) Green CI, merge, refactor immediately after** | Same refactor work, deferred by one merge-and-branch cycle. Adds: a second PR, a second CI round, and -- concretely -- the wrong shape lands on `main` for however long the second PR takes to review, meaning any M10+ work someone starts from `main` in that window inherits the wrong shape by copying `services/identity`'s current pattern. | Medium. "Proof-of-tooling" value is real (confirms Maven/ArchUnit/CI mechanics work end-to-end) but is fully preserved by (A) too, since (A) still runs the same CI before merge -- (B) buys nothing (A) doesn't already get, while adding the risk that "get it green, ship it" becomes the precedent for the next gap found. |
| **(C) Accept as-is, refactor in M10** | Defers the cost, but multiplies it: M10 is exactly when `services/device`, `services/asset`, and others are scaffolded, almost certainly by copying `services/identity`'s pattern (its own README already documents itself as the walking-skeleton exemplar). Refactoring after 3-4 services depend on `iotee-common`'s RBAC classes and `iotee-adapter-web-spring` means touching N services' `pom.xml`s and import statements instead of one, plus deprecating a published shared-jar API that (per the rule's own rationale) other teams may have started building against in good faith. | High. This is the "merging a known-wrong shape creates confusing history" cost the repository owner named, concretely realized: every subsequent commit that depends on `iotee-common`'s RBAC classes is itself something to unwind later, and ADR history would show the platform adopting, then reversing, a foundational sharing pattern in public view. |

Cost of (A) is small and bounded *today* specifically because Track B is
still a single, unmerged, one-service branch. That property degrades
with every service added under the current shape -- the case for (A) is
strongest right now and gets weaker (i.e., (C)'s cost keeps climbing)
the longer it is deferred.

## 4. Proposed target structure

The repository owner's sketch is directionally correct; this section
extends it to cover RBAC/correlation (which the sketch's example did
not yet address) and to state where per-service Spring wiring goes.

```
contracts/
  events/v1/envelope.proto        # CloudEvents-shaped envelope (ADR 0012 Decision 4) -- contract source only
  cfg/cfg.schema.json             # draft cfg.yaml schema (ADR 0012 Decision 3) -- contract source only
  # architecture/ (see below) -- ArchUnit rules and shared build-time fixtures, also contract-shaped:
  # a rule is a build-time assertion about shape, not a runtime dependency.

services/identity/
  pom.xml                         # protobuf-maven-plugin configured with an additional proto source
                                   #   root pointing at ../../contracts/events/v1 -- generates its OWN
                                   #   Envelope classes into its OWN target/generated-sources, package
                                   #   com.iotee.platform.identity.generated.envelope (or similar) --
                                   #   no dependency on any shared "common" artifact for this.
                                   # No dependency on iotee-common or iotee-adapter-web-spring (both
                                   #   retired as shared artifacts -- see below).
  src/main/java/.../domain/       # Tenant, TenantId -- unchanged; already correctly isolated (Section 1)
  src/main/java/.../core/         # TenantPermissionsHandler -- unchanged in shape, but its RBAC/ABAC
                                   #   types (Permission, Role, RbacRegistry, AbacContext, AbacDecision)
                                   #   move INTO this service's own core/rbac/ package, as this
                                   #   service's own implementation artifact, not an import from
                                   #   common/. A future services/device that also needs an RBAC check
                                   #   authors its own local RBAC types the same way -- duplicated code,
                                   #   zero shared runtime, per the rule.
  src/main/java/.../web/          # TenantPermissionsController -- unchanged in shape; its own local
                                   #   Spring @RestController, HandlerInterceptor and AutoConfiguration
                                   #   for correlation-ID binding now live here too (moved out of
                                   #   adapters/web-spring/), as this service's own Spring wiring.
  src/main/java/.../correlation/  # CorrelationIdContext + CorrelationIdConstants -- moved from common/,
                                   #   now this service's own copy. Small (under 150 lines combined);
                                   #   duplication cost across N services is the accepted price of zero
                                   #   shared runtime, exactly as the rule states.

architecture/                     # NEW top-level module: build-time-only ArchUnit rule definitions
  src/main/java/.../ArchRules.java  #   and reusable rule *factories* (not shared runtime business logic --
                                     #   an ArchRule object is a build-time assertion, explicitly named
                                     #   shareable by the rule). Each service's own architecture test
                                     #   imports this as a test-scope-only dependency and calls e.g.
                                     #   ArchRules.noSharedBusinessLogicAcrossServices(), supplying its
                                     #   own package root. Never a main-scope/runtime dependency of any
                                     #   service -- enforced by the Enforcer rule in Decision 5.

# iotee-common and iotee-adapter-web-spring, as shared-JAR Maven modules, are RETIRED, not renamed.
# Their content does not move to a new shared module under a different name -- that would just
# relocate G1-G4, not fix them. Their code moves INTO services/identity (above) as that service's own.
```

Why this shape, specifically:

- **Contracts are files, not jars.** `contracts/` holds `.proto` and
  `.schema.json` *source*, never generated code and never a `pom.xml` of
  its own that produces a jar. Nothing in `contracts/` is ever a Maven
  dependency; it is a source root each service's own build points at.
  This is the one structural change that makes "share the `.proto`, not
  the generated class" mechanically true rather than a convention
  someone has to remember.
- **Duplication over false sharing.** RBAC/correlation code duplicated
  per service is the explicit, intended cost of this rule -- the
  repository owner's own wording (`common.jar` with a shared helper is
  the named anti-pattern) leaves no smaller-footprint alternative that
  still satisfies "no shared executable Java libraries containing...
  helper implementations." A service that later needs to change how it
  binds correlation IDs to MDC can do so without a cross-service
  version negotiation -- the actual benefit the rule is buying.
- **`architecture/` is new, not a renamed `common/`.** It holds only
  `ArchRule` factories and their negative-test fixtures -- explicitly
  the one category ("architecture rules, build-time") the rule already
  says is fine to share, so it earns a place at the top level without
  contradicting anything. It must never contain a class any service
  imports at `main` scope; Decision 5's Enforcer rule makes that
  mechanical, not just documented.
- **No new shared Spring-wiring module.** `adapters/web-spring` is
  retired outright, not kept and merely "made smaller." A future
  service's own local Spring wiring (an interceptor, an
  `@AutoConfiguration` class if the service is large enough to want its
  own) is that service's own code, written once per service -- typically
  under 60 lines total per the current `adapters/web-spring` line count,
  a small and bounded duplication cost.
- **`adapters/{mqtt,apisix,storage}` from ADR 0011's original skeleton
  are unaffected by this change** and are out of scope for Track B/this
  audit (they don't exist yet) -- they remain the correct pattern for a
  *vendor-SDK* adapter (one real backend behind a contract, consumed by
  whichever specific service needs that backend), which is a genuinely
  different shape from "Spring wiring every service needs," and this
  ADR does not challenge that pattern.

## 5. New ADR needed: yes -- proposed content for ADR 0013

This document, once agreed and merged, *is* ADR 0013 (per the numbering
convention, it is renamed at merge from
`XXXX-proposed-microservice-autonomy.md` to
`<NNNN>-microservice-autonomy.md`). Its Decisions section, to be
finalized at merge, should read:

### Decision 1: The rule, made binding, verbatim

The repository owner's clarification (quoted in full under Context,
above) becomes this platform's binding definition of "no shared
runtime." Any future ambiguity about whether something is a "contract"
or "shared runtime" is resolved by asking: does consuming this artifact
require executing code someone else wrote to implement a business
decision (RBAC, tenancy, correlation policy, ...), or does it require
only interpreting a static, code-generation-input file this consumer
compiles for itself? The former is never shared; the latter always may
be.

### Decision 2: What counts as a contract vs. runtime -- the concrete test

A contract artifact:
- Is a `.proto`, an OpenAPI document, a JSON Schema file, or an ArchUnit
  rule/rule-factory definition.
- Produces no behavior on its own -- it is either data-interchange
  shape (Protobuf/OpenAPI/JSON Schema) or a build-time assertion
  (ArchUnit) that runs during `mvn verify` and never ships in a runtime
  artifact.
- May be checked out, read, and independently code-generated against by
  any number of services without those services depending on each
  other or on a shared jar.

Runtime code -- never shared, regardless of how small or "pure" it
looks:
- Anything with a method body implementing a decision (RBAC evaluation,
  correlation-ID trust-boundary policy, a domain invariant, a mapping
  between a wire shape and a domain object).
- Anything that would need a version bump negotiated across multiple
  services to change (the exact failure mode a shared jar creates and a
  regenerated-per-service contract avoids).
- Generated Protobuf/gRPC stub *classes* themselves are runtime code,
  not contract artifacts, once compiled -- they must be generated by
  each consuming service's own build from the shared `.proto` *source*,
  never distributed as a compiled shared jar (Decision 2 of this ADR
  directly resolves G5).

**Standard format/serialization libraries are explicitly allowed as
ordinary per-service third-party dependencies -- resolved, not merely
assumed.** The rule prohibits *project-authored* runtime business logic
shared across services; it does not prohibit any service from
depending on Jackson, `protobuf-java`, or the CloudEvents SDK
(`io.cloudevents:cloudevents-core`/`cloudevents-protobuf`), each
declaring the same third-party coordinates independently, the same way
every service already independently depends on `spring-boot-starter-web`
without that being "shared runtime." This is stated explicitly, in the
ADR itself, precisely because the alternative failure mode is real: a
well-meaning contributor "purifying" a service by hand-rolling its own
Protobuf/CloudEvents serialization to avoid an apparent dependency
violation, which would be a strictly worse outcome (bespoke,
undertested wire-format code, in the name of a rule that never asked
for that). The test stays the one this Decision already states: does
consuming it require executing *this platform's own* business
decision, or does it require only a standard, independently-versioned
third-party library any of many unrelated projects also depend on
as-is? The former is never shared; the latter is unrestricted.

### Decision 3: Track B refactors now (Section 3, Path A)

Adopted per the repository owner's own prior and this audit's cost
analysis in Section 3.

### Decision 4: This ADR supersedes specific passages of ADR 0011 and ADR 0012

Per this audit's constraint (existing ADRs are not rewritten), the
supersession is recorded here rather than edited into those files:

- ADR 0011's M0-M8 mapping table, "M0 Foundation" row ("a shared
  `common` library's event/command envelope validation... used by every
  service") is superseded by Decision 2 above: the envelope's `.proto`
  source may be shared; a compiled validation library may not.
- ADR 0011's "Repo structure proposal" note describing `common/` as "a
  shared test-fixture module every service's build depends on" is
  superseded by Section 4's `architecture/` module description: shared
  ArchUnit rule *definitions* are fine (and were already the one
  correct part of that sentence); a shared *runtime* dependency is not.
- ADR 0012 Decision 6's instruction that the walking skeleton "consults
  `common/`'s RBAC primitive" is superseded: the walking skeleton
  consults its own local RBAC primitive, authored once for
  `services/identity` and not exported for reuse.

### Decision 5: Mechanical enforcement

Two complementary mechanisms, both build-time, mirroring this
repository's `scripts/check.py` discipline:

1. **Maven Enforcer Plugin, `bannedDependencies` rule**, declared once
   in the parent `pom.xml` and inherited by every module: no
   `services/*` or `adapters/*` module may declare a dependency on
   another `services/*` or `adapters/*` module's artifact (this already
   mostly falls out of removing `iotee-common`/`iotee-adapter-web-spring`
   as dependency targets, but the Enforcer rule makes the absence
   permanent and mechanically checked, not just currently true because
   no one has added a new one yet).
2. **A new ArchUnit rule in `architecture/`**, run by every service's
   own build against its own compiled classes: no class in this
   service may reside in a package outside its own service's root
   *and* be depended on by another service in the same build (catches
   the case Enforcer's POM-level check cannot: a service that vendors
   another service's source directly, or a future contributor who adds
   a shared module without going through a `pom.xml` dependency, e.g.
   an unpacked-jar hack). Each rule needs the same negative-test-fixture
   discipline already used throughout Track B (Section 1) -- a planted
   violation, asserted to fail, with the specific forbidden package
   named in the assertion.

### Decision 6: Duplication is accepted cost, not technical debt -- with no ceiling

Explicitly stated so a future PR reviewer does not "fix" the intended
duplication by reintroducing a shared module: near-identical
correlation-ID binding code, or near-identical small RBAC primitives,
appearing in `services/identity`, `services/device`, etc. is the
correct, intended state under this ADR, not an oversight to consolidate.

**Resolved (repository owner, this ADR's ratification round): no
ceiling.** Duplication is the price of autonomy, stated as a decision
principle rather than a threshold to monitor. Repeated near-identical
code across services is read as a signal that a service boundary is
drawn wrong (the two services may not actually be as separate as their
deployment topology suggests), never as a case for introducing a shared
JAR to remove the duplication. If a boundary question arises from
observed duplication, it is resolved as a bounded-context/domain
question (is this really two services?), not as a build-dependency
question (should this become `common` again?). No future ADR may
reopen this as "duplication has now crossed N services, time for a
shared library" -- that reopening is exactly the reasoning this
Decision forecloses.

### Decision 7: `contracts/` location -- repository root, sibling to `spec/`

**Resolved (repository owner):** `contracts/` lives at the repository
root, as a sibling to `spec/`, `services/`, and `adapters/` -- not
nested under `spec/`. Rationale, as stated: `contracts/` is
language-neutral and shared by both Java and Python consumers (the
platform's own services today, and the Python RAG subsystem's own
future Kafka consumers per ADR 0011 Decision 1), so it does not belong
under `spec/`, which ADR 0012 Decision 7 already scoped specifically to
the Python *reference implementation*, a different and narrower thing
than a cross-language contract source directory. This confirms Section
4's sketch as final, not merely illustrative.

### Decision 8: `architecture/` module is test-scope only, explicitly

**Resolved (repository owner):** the `architecture/` module (Section
4) is allowed to be a shared dependency precisely because, and only
because, it is `test`-scope in every consumer's `pom.xml` -- never
`compile`/`main` scope. This is the same build/test-time vs. runtime
distinction Decision 2 already draws for ArchUnit rules generally, now
stated as its own explicit module-level constraint so it cannot be
missed when `architecture/` is scaffolded: a `pom.xml` that declares
`iotee-architecture` (or whatever it is named) without `<scope>test</scope>`
is itself the violation, and Decision 5's Maven Enforcer rule set
(below) is extended to check this specifically -- a `bannedDependencies`
(or `requireUpperBoundDeps`-adjacent) rule keyed on scope, not just on
artifact identity, so a compile-scope `architecture/` dependency fails
the build the same way a compile-scope dependency on another service
would.

### Decision 9: Per-service Protobuf generation -- confirmed as the standard idiom

**Resolved (repository owner):** each service's `pom.xml` configures
`protobuf-maven-plugin` with an additional proto source root pointing
at `../contracts/events/v1` (or the relevant `contracts/` subpath),
generating that service's own classes into its own
`target/generated-sources` on every build -- exactly Section 4's
sketch, now confirmed as the standard idiom for every service that
consumes a `.proto` contract, not a Track-B-specific special case.
Every future service (`services/device`, `services/asset`, ...) that
needs the envelope contract configures this identically; no service
ever adds a `<dependency>` on another module to get the envelope
classes.

## 6. Remaining open question for the repository owner

Four of the five questions this audit originally raised are resolved
above (Decisions 2, 6, 7, 8, 9). One remains open:

1. **Should this ADR's Enforcer/ArchUnit enforcement (Decision 5) be
   built as part of Track B's own refactor (Path A, Section 3), or as
   an immediate follow-up commit once Track B's refactored shape
   lands?** This audit did not assume an answer -- Section 3's cost
   estimate for Path A covers the *structural* move (contracts/,
   per-service RBAC/correlation, retiring the two shared modules) but
   treats Decision 5's mechanical enforcement (now including Decision
   8's scope-aware Enforcer rule) as a closely related but separable
   unit of work, since it is genuinely new rule-authoring rather than a
   move of existing code. Not blocking this ADR's own merge; worth
   settling before Track B's refactor commit is scoped in detail.

## Non-goals (this document does not do)

- Does not write any Java code, refactor Track B itself, or fix the
  currently-pending CI failure in `adapters/web-spring` (its
  correctness depends on whether that module survives the refactor at
  all, per the stated constraint) -- this is audit and proposed-decision
  content only.
- Does not rewrite ADR 0011 or ADR 0012; Decision 4 records supersession
  by citation rather than editing those files.
- Does not design the Maven Enforcer / ArchUnit rule implementations in
  full (signatures, exact package names) -- Decision 5 states the
  mechanism and intent; implementing it is Track B refactor work, not
  this audit.
- Does not resolve Open Question 2 (contracts/ location) or Open
  Question 4 (duplication ceiling) -- both are named explicitly as
  owner decisions this document cannot make on its own.
