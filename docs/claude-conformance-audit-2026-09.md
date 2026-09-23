# CLAUDE.md conformance audit -- 2026-09

Scope: `CLAUDE.md` as currently checked out (working tree, not yet
committed -- see "Audit scope note" below) against the repository state
at commit `a450156` on `feature/track-b-java-bootstrap` (based on
`main` at `ef5c4c5`), plus two uncommitted working-tree edits: `CLAUDE.md`
itself and a set of internal reference fixes in
`docs/adr/0013-microservice-autonomy.md`. No code was changed to produce
this report; every finding below is a read-only observation.

**Audit scope note:** `CLAUDE.md` and `docs/adr/0013-microservice-autonomy.md`
currently show as modified in `git status` and have not been committed.
This audit evaluates the content on disk, since that is what a new
session would actually read, but flags explicitly wherever a finding
depends on content that is not yet part of any commit.

## Method

- Read `CLAUDE.md` in full (current working-tree content).
- Read every ADR it cites, the root `pom.xml`, `architecture/pom.xml`,
  `services/identity/pom.xml`, `services/identity/README.md`,
  `.github/workflows/ci.yml`, `docs/adr/README.md`, `docs/architecture.md`,
  `docs/requirements-addendum.md`, and `spec/scripts/check.py`.
- Ran `python spec/scripts/check.py` directly.
- Attempted `mvn -f pom.xml verify`; `mvn` is not installed in this
  environment (`bash: mvn: command not found`) -- see Section 2.
- Inspected `git log --oneline --all --graph` and per-commit
  author/committer identity for PR/merge/review evidence.
- Grepped the full tree for stale references to retired module names
  (`common/`, `adapters/web-spring/`), for the old ADR 0013 placeholder
  filename, and for TB CE/Kafka/APISIX/Istio artifacts.

---

## 1. Rule-by-rule verification

Legend: **Followed** = evidence found and current; **Aspirational** =
documented, not contradicted, but no code/process exercises it yet;
**Mechanical** = a build/test/script actively checks it; **Procedural**
= relies on a human following a convention, nothing checks it
automatically; **Violation** = evidence contradicts the rule.

### 1.1 "All changes require PR, CI, and human review; an agent must not approve its own work."

- **Evidence it is followed:** Every merge commit on `main`
  (`78cbfae`, `f616fb4`, `b2b3db0`, `688f797`, `92881fb`) is authored
  by `Ahmed Mazen <54961882+amazen33@users.noreply.github.com>`, the
  repository owner, while the content commits on the merged branches
  are authored by `Claude (via amazen33)
  <amazen33@users.noreply.github.com>`. This is the expected shape:
  the agent proposes, the human merges. No commit on `main` shows the
  agent's own identity as the merger.
- **Mechanical or documentary?** **Procedural**, not mechanical, from
  what is visible here. Nothing in this repository (no branch
  protection config, no CODEOWNERS file, no GitHub Actions required-
  review gate) is checked into the tree to enforce it -- GitHub's
  actual branch-protection settings live outside the repository and
  are invisible to this audit. The rule is followed by observed
  practice, not by a mechanism this audit can point to.
- **Current status / violation:** The most recent work, commit
  `a450156` ("Refactor Track B against ADR 0013"), is sitting
  unmerged and unpushed on `feature/track-b-java-bootstrap` --
  authored by the agent, not yet reviewed, not yet in a PR. This is
  not a violation (the rule is about merging, and this hasn't merged),
  but it means the rule's most important compliance evidence -- "did a
  human review this before it reached `main`" -- **cannot be produced
  yet** for the largest single change in the repository's Java
  history. Flagging this as a gap to close, not a breach: the same
  push-blocked-by-proxy condition that stopped every prior commit in
  this engagement from reaching GitHub directly applies here too.
- Whether GitHub Actions CI actually ran and passed on any past PR
  cannot be confirmed from this environment: the GitHub API is not
  reachable here (same network restriction that blocks `git push`).
  The merge commits above are evidence a human clicked merge; they are
  not evidence CI was green when they did. **This is a real
  verification gap in this audit itself**, not a repository defect --
  noted so it isn't mistaken for confirmed CI evidence.

### 1.2 Prompt frame (milestone, bounded context, verified inputs, acceptance criteria, security constraints, intended files, tests, non-goals; assumptions vs. observations)

- **Evidence it is followed:** `services/identity/README.md`'s
  "Explicit non-goals" section and "Verification status" section, and
  the extensive per-file Javadoc throughout `services/identity/src`
  distinguishing what is implemented from what is deferred, are
  consistent with this framing. ADR 0013 itself
  (`docs/adr/0013-microservice-autonomy.md`) explicitly separates
  "aligned items" from "gaps," which mirrors assumption-vs-observation
  discipline.
- **Mechanical or documentary?** **Procedural.** This is a
  conversational/process discipline; nothing in the repository checks
  that a commit message or PR description actually stated a milestone,
  bounded context, etc.
- **Violation:** None found.

### 1.3 Secrets and PII

- **Evidence it is followed:** No secret-shaped strings, `.env` files,
  or credential material found in the tracked tree. `spec/scripts/check.py`
  mechanically bans `socket`, `requests`, `paramiko`, `boto3`,
  `psycopg2`, etc. from `domain_core` and `migration_studio` (lines
  ~23-27), which is a stronger, mechanically-checked version of "no
  unrestricted cloud commands." `spec/migration_studio/vault.py`
  implements the secret-reference pattern directly: `store_secret`
  accepts a raw value exactly once and returns only an opaque
  `SecretReference`, never the value itself (module docstring, lines
  1-15).
- **Mechanical or documentary?** **Mechanical** for the import
  denylist (enforced by `check.py`, currently passing, 437/437 tests).
  **Aspirational/documentary** for "enter source secrets once directly
  into the scaffold-configured vault" as a live operational practice --
  `vault.py` is a provider-neutral abstraction with no real backend
  wired up yet (its own docstring: "Anything reaching a real backend
  ... is out of scope for M1"). The pattern is correctly modeled, not
  yet operated against a real vault.
- **Violation:** None found.

### 1.4 ThingsBoard preservation (authorized export, inventory, shadow comparison, approval; screenshots describe visible UI only)

- **Evidence it is followed:** `docs/tb-ce-inventory.md` and
  `tb-extensions/manifest.yaml` exist and are the Track A deliverable
  ADR 0012 called for. `spec/migration_studio/shadow_parity.py` and
  its tests implement the shadow-comparison concept in the reference
  layer.
- **Mechanical or documentary?** **Aspirational** for the Java
  platform: no actual TB CE export/import code exists yet anywhere in
  `services/` (only the walking-skeleton identity service exists). The
  rule is real and mechanically shaped in the Python reference
  (`spec/migration_studio/`), but that tree is frozen (ADR 0012
  Decision 8) and is not the platform -- so this rule has **not yet
  been exercised by the actual Java platform it now governs**.
- **Violation:** None found.

### 1.5 Constitutional engineering rules (ADR 0011 Decision 4)

CLAUDE.md's one-paragraph summary was checked word-for-word against
the actual ADR 0011 Decision 4 text (`docs/adr/0011-platform-architecture-and-language-stack.md`,
lines ~148-172). It is a faithful, non-distorting paraphrase.

Per sub-rule:

- **No secrets/PII/payment data/credentials in source, Git, events,
  logs, metrics, prompts, fixtures:** Mechanically checked for the
  Python reference tree only (see 1.3). **Not yet checked anywhere for
  the Java platform** -- no equivalent scanner runs over
  `services/identity`'s code, logs, or fixtures. Aspirational there.
- **Server-issued correlation IDs preserved end-to-end:**
  **Followed and exercised**: `services/identity/src/main/java/.../correlation/CorrelationIdContext.java`,
  `CorrelationIdHandlerInterceptor.java`, `CorrelationIdServletFilter.java`
  implement this, with `CorrelationIdHandlerInterceptorTest`
  (`preHandleBindsToMdcSoLogLinesCarryTheCorrelationId`) asserting the
  real MDC binding rather than just the interceptor's own bookkeeping
  (per that test's own comment and `services/identity/pom.xml`'s
  logback-classic test dependency, both cite this explicitly). This is
  real code with a real test, though it has never been compiled (see
  Section 2).
- **Signed, device-verified firmware; WebSockets report progress only,
  never the binary:** **Aspirational only.** No firmware code exists
  anywhere under `services/`. `spec/firmware/delivery.py` models the
  distinction in the frozen reference tree, but no Java equivalent
  exists yet -- this rule has no platform code to violate or satisfy.
- **No destructive/infra/deploy/secret-rotation action or gate bypass
  without explicit human approval:** No infra-provisioning or
  destructive-action code exists in the Java platform at all yet (no
  `services/deployment_studio`), so this is vacuously true for now,
  the same way the sibling-bounded-context ArchUnit rule is
  vacuous by design (`IdentityArchitectureRulesTest.allFiveSiblingIsolationRulesAreCurrentlyVacuous`,
  which mechanically fails the day it stops being vacuous -- a good
  pattern, not yet applied to this rule).
- **No DR/compliance/migration completion claims from local tests
  alone:** **Followed and mechanically demonstrated**:
  `spec/tests/test_adapters_worm_s3_live.py` uses
  `@unittest.skipUnless(os.environ.get("WORM_S3_BUCKET"), ...)`
  reported as **skipped**, never as passed, when no real bucket is
  configured -- exactly the "blocked, not passed" discipline, and it
  is the one test currently producing that skip in the 437/1-skipped
  result. `services/identity/README.md`'s "Verification status"
  section applies the identical discipline to `mvn verify` in prose
  (not yet mechanically -- see Section 2).
- **RBAC + ABAC + mTLS/JWT/SSO multi-tenancy:** **Partially followed,
  mostly aspirational.** RBAC/ABAC exist as real code in
  `services/identity/src/main/java/.../rbac/` (`AbacContext`,
  `RbacRegistry`, `Role`, `Permission`), with the `AbacContext`
  currently hardcoded to `alwaysPermit()` per its own Javadoc (an
  intentional stub, correctly documented as such). mTLS/JWT/SSO: no
  code anywhere. This rule is real in miniature (RBAC scaffolding) and
  aspirational in full.

### 1.6 Milestones section (ADR 0011/0012/0013 summary, M9 status)

- **Evidence it is followed:** Cross-checked against the actual ADRs.
  ADR 0011 Decision 9 and ADR 0012 Decision 8 do freeze `spec/` as
  stated. ADR 0012's Track A/Track B split and ADR 0013's "no shared
  runtime JAR" summary both match their source ADRs' actual Decisions.
- **One drift found:** CLAUDE.md's Post-M8 bullet for ADR 0012 still
  reads "Track B (Java bootstrap: Maven parent, `common/` primitives,
  ArchUnit gates, `services/identity` walking skeleton)" -- describing
  Track B's **original, now-retired** shape (the one ADR 0013 replaced).
  This is technically accurate as history (that is what ADR 0012 asked
  for) but reads, in a document that also says "M9 is in progress...
  Track B refactor against ADR 0013 pending," as if `common/` still
  exists. It does not (see Section 3). Minor, but worth tightening:
  either mark it explicitly as "(original shape, since retired by ADR
  0013)" or drop the `common/` mention.
- **"M9 is in progress. Track A merged; Track B refactor against ADR
  0013 pending."** -- this line is now **stale relative to the working
  tree**: the Track B refactor is no longer pending, it is committed
  (`a450156`), just not yet merged/pushed. Whether this counts as
  "pending" depends on whether the sentence means "not started" (false)
  or "not merged" (true) -- worth disambiguating once this lands.

### 1.7 Repository layout

Checked every documented path against the actual tree:

| Documented path | Exists? | Note |
|---|---|---|
| `contracts/` | Yes | `contracts/README.md`, `contracts/cfg/`, `contracts/events/v1/` all present |
| `architecture/` | Yes | test-scope only, as documented |
| `services/<name>/` with `core/`/`web/` | Yes | `services/identity/` only, matches |
| `adapters/<provider>/` | **No** | Documented as a top-level directory; does not exist anywhere in the repo yet. Only `spec/adapters/worm_s3` exists, and that is explicitly the frozen Python reference tree, not the platform path CLAUDE.md describes. Not a violation -- no adapter has reached the platform yet -- but it is **entirely aspirational**, not "documented and followed." |
| `deploy/` | **No** | Same as above: documented, does not exist. Aspirational only. |
| `spec/` | Yes | matches |
| `docs/`, `docs/adr/` | Yes | matches |
| `.github/workflows/` | Yes | matches, see 2.2 for whether it does what's documented |

- **Violation:** None -- the missing directories are future-milestone
  scope, not contradicted scope. But two of seven documented top-level
  entries (`adapters/`, `deploy/`) currently describe nothing that
  exists, which is worth knowing plainly rather than assuming the
  layout section describes the repository as it is today.

### 1.8 Gates table

See Section 2 in full. Summary: Python gate **Followed and Mechanical,
currently passing**. Java gate **documented, present in CI config,
never successfully executed in any environment used to prepare this
platform's code, including this one** -- see Section 2 for exactly what
"never executed" means here.

### 1.9 Architectural principles

#### Microservice autonomy (ADR 0013)

- **Zero shared runtime:** **Mechanical, on paper.** Root `pom.xml`'s
  `maven-enforcer-plugin` `enforce-no-shared-runtime` execution bans
  `com.iotee.platform:*` at `compile`/`runtime` scope
  (`pom.xml`, `<execution id="enforce-no-shared-runtime">`).
  `IdentityArchitectureRulesTest.noProductionClassResidesInARetiredSharedPackage`
  is the source-level equivalent. Grep confirms no
  `com.iotee.platform:*` dependency anywhere at compile/runtime scope
  outside `iotee-architecture` at test scope
  (`services/identity/pom.xml`, `architecture/pom.xml`).
  **However: this mechanism has never actually run.** `mvn` is not
  installed in this environment, and per this engagement's own
  history (`services/identity/README.md`, "Verification status"),
  every environment used to write this code has had Maven Central
  network access blocked. The Enforcer rule and the ArchUnit rule are
  both **well-formed source code that has never been compiled or
  executed anywhere.** This is the single most important
  "looks fine, hasn't been exercised" finding in this audit: a rule
  that is architecturally correct on inspection is not yet proven to
  even parse as valid Maven/ArchUnit configuration, since nothing has
  ever run `mvn verify` against it.
- **Contracts over libraries:** **Followed**, per-service Protobuf
  generation (`services/identity/pom.xml`'s `protobuf-maven-plugin`,
  `protoSourceRoot` pointed at `../../contracts/events/v1`) is
  correctly structured, but likewise unexecuted (protoc has never run
  against this configuration).
- **Standard format libraries allowed:** **Followed.** Jackson,
  `protobuf-java`, CloudEvents SDK, `json-schema-validator` all appear
  as ordinary dependencies with no project-authored wrapper "purifying"
  them. No contradiction found anywhere in either pom.xml.

#### Hexagonal architecture

- **Pure domain core:** **Followed and mechanically checked** (on
  paper, same caveat as above): `IdentityArchitectureRulesTest.identityCoreDoesNotDependOnSpring`
  and `identityRbacDoesNotDependOnSpring`. Direct grep confirms zero
  `org.springframework` imports in `core/` or `rbac/` today. Real and
  currently true; enforcement mechanism unexecuted.
- **Database independence:** **Vacuous, by construction** -- no
  persistence code exists yet anywhere (`grep` for
  `jakarta.persistence`/`org.hibernate` across `services/` and
  `architecture/` returns nothing). The rule cannot currently be
  violated because there is nothing to violate it. Not a defect, but
  it means this rule is **entirely untested in practice**, unlike the
  Spring-freedom rules which have real classes to check against.
- **Protocol independence:** Same as above -- only REST exists
  (`TenantPermissionsController`); no gRPC or Kafka adapter exists yet
  to check independence against.

#### Plug-and-play infrastructure

- **Evidence it is followed:** None -- **entirely aspirational.** No
  TB CE integration code, no Kafka client, no CDC/Debezium config, no
  APISIX config, no storage (MinIO/S3) adapter, no payment-provider
  code exists anywhere in the repository outside the frozen `spec/`
  reference tree and `tb-extensions/manifest.yaml` (an inventory
  document, not integration code). This entire principle is currently
  a stated intent with zero code surface to check it against.
- **"Enforcement: Maven Enforcer + ArchUnit autonomy rules, both in
  the build"** -- true only for the shared-runtime rule (see above);
  there is no plug-and-play-specific Enforcer/ArchUnit rule yet (e.g.
  nothing yet bans a direct `com.thingsboard:*` or
  `org.apache.kafka:*` SDK import outside an adapter module, because
  no such import exists yet to ban). This sentence in CLAUDE.md reads
  as more mechanically enforced today than it actually is.

### 1.10 ADR conventions

- **Evidence it is followed:** Strong, repeated, real evidence: ADR
  0011 documents its own renumbering from a requested 0009
  (`docs/adr/0011-...md`, "Numbering note"); `docs/adr/README.md`
  documents the same collision as the reason for the convention; ADR
  0012 and ADR 0013 were both drafted at `XXXX-proposed-<slug>.md`
  paths and renamed at merge (commits `4553a32` "ADR 0012: assign real
  number at merge", `ef5c4c5` "ADR 0013: assign real number at merge").
  This convention has been exercised three times and followed
  correctly every time.
- **Mechanical or documentary?** **Procedural.** No CI check verifies
  a new ADR's filename against the numbering convention, or that
  "superseding" language exists rather than an edit to an old ADR.
  Purely a followed human convention.
- **Violation:** None. (The two self-referential mentions of the old
  `XXXX-proposed-microservice-autonomy.md` path still inside
  `docs/adr/0013-microservice-autonomy.md` were corrected, in the
  working tree, to past tense during this session's earlier task --
  not yet committed; see Section 4.)

### 1.11 Stage-completion checklist

- **Evidence it is followed:** `services/identity/README.md`'s
  "Verification status" section explicitly itemizes what was and was
  not verified and states the Java gate as blocked rather than passed
  -- textbook compliance with this checklist's last bullet. Code, tests,
  and docs exist together for every stage inspected (Track A, Track B
  original, Track B refactor). No deployment artifacts exist yet for
  any Java-platform stage (no Helm chart, no Ansible playbook, no
  Terraform) -- consistent with "deployment artifacts where the stage
  touches infrastructure," since no stage has touched infrastructure
  yet.
- **Mechanical or documentary?** **Procedural.** Nothing in CI checks
  that a PR includes docs or tests; this is enforced by whoever reviews
  the PR, per 1.1's process, not by a script.
- **Violation:** None found.

### 1.12 Supply-chain security scanning (ADR 0014, planned)

- **Evidence it is followed:** Correctly deferred. No Trivy config, no
  cosign/Sigstore signing step, no SBOM-generation step anywhere in
  `.github/workflows/` or the repo. CLAUDE.md's own caveat ("do not
  assume a green build means a clean bill of health") is accurate --
  there is genuinely no dependency/image/IaC/secret scanning in CI
  today.
- **Mechanical or documentary?** N/A -- correctly not yet built,
  correctly documented as not yet built.
- **Violation:** None.

### 1.13 M0-M8 legacy constraints (still binding)

- **Evidence it is followed and mechanically enforced:**
  `spec/scripts/check.py`'s `_FORBIDDEN_IMPORTS_FOR_DOMAIN_MODULES`
  denylist (bans `socket`, `subprocess`, `boto3`, `psycopg2`, etc. from
  `domain_core`/`migration_studio`) and `_check_import_allowlist`
  (inverse allowlist for `deployment_studio` and `adapters.worm_s3`)
  are real, running, currently-passing checks (`python spec/scripts/check.py`,
  437 tests, 1 skipped, all green). This is the single strongest
  "mechanically enforced, not just documented" finding in this audit.
- **Migration Studio data import governed by its own ADR (0002):**
  `docs/adr/0002-migration-studio-foundation.md` exists and is cited
  correctly.
- **Violation:** None found.

---

## 2. Gate integrity

### 2.1 `python spec/scripts/check.py`

**Result: PASS.** Ran directly in this environment:

```
Ran 437 tests in 4.148s
OK (skipped=1)
```

The one skip is `spec/tests/test_adapters_worm_s3_live.py`'s
`LiveS3WormStoreTests`, correctly gated by
`@unittest.skipUnless(os.environ.get("WORM_S3_BUCKET"), ...)` and
reported as skipped, not passed, per the contract's own rule for
unavailable external verification. This is the gate working exactly as
CLAUDE.md describes it.

### 2.2 `mvn -f pom.xml verify`

**Result: BLOCKED, not passed, not failed.** `mvn` is not an installed
binary in this environment (`bash: mvn: command not found`) -- this is
a stronger statement than "network blocked": the tool itself is absent
here, so the command could not even be attempted, let alone reach a
network failure. Per this engagement's own prior documentation
(`services/identity/README.md`, "Verification status"), every other
environment used to prepare this Java code has had Maven Central
network access blocked instead, so the underlying gate has **never
completed successfully in any environment used to build this
platform, including the one producing this audit.**

Consequence for this audit's own findings: every ArchUnit rule,
Enforcer rule, and Protobuf-generation configuration cited as
"mechanical" in Section 1 is mechanical **by design and by source
inspection only**. None of it has been proven to actually compile,
let alone pass. The CI workflow (`.github/workflows/ci.yml`,
`java-platform-bootstrap` job) is configured to run
`mvn -B -f pom.xml verify` on GitHub-hosted runners, which do have
normal internet access and do ship Maven -- so this gate likely *can*
run for real there. But no evidence available to this audit (no reachable
GitHub Actions run history) confirms it ever has, for the current
module shape (`architecture/` + `services/identity`, post-refactor).
The most recent Java commit (`a450156`) has not been pushed, so no CI
run has been triggered for it at all yet.

**This is documented here as blocked, not passed, per the contract's
own rule for unavailable external verification** (CLAUDE.md, Gates
section and Stage-completion checklist).

---

## 3. Drift report

### 3.1 Stale references

None found pointing at nonexistent files. The two hits for
`XXXX-proposed-microservice-autonomy` and `<NNNN>-microservice-autonomy`
inside `docs/adr/0013-microservice-autonomy.md` are correctly
historical (describing the file's own former placeholder path in past
tense) as of this session's earlier edit -- not yet committed.
`docs/tb-ce-inventory.md`'s `common/proto/...` hit is an upstream
ThingsBoard GitHub URL, unrelated to this repository's retired
`common/` module -- a false positive on the grep, not a real stale
reference.

### 3.2 Directories not in the documented layout

- `spec/` contains subpackages (`billing/`, `deployment_studio/`,
  `domain_core/`, `evidence/`, `firmware/`, `gateway/`, `ingestion/`,
  `migration_studio/`, `reporting/`, `foundation/`, `adapters/`,
  `fixtures/`, `scripts/`, `tests/`) that CLAUDE.md's layout diagram
  collapses into a single `spec/` line with the comment "frozen M0-M8
  Python reference implementation (not the platform)." This is
  intentional compression, not drift -- the diagram is explicit that
  `spec/`'s internals are out of scope for the platform layout it is
  describing.
- Conversely, two directories CLAUDE.md's layout **does** document
  (`adapters/`, `deploy/`) do not exist anywhere in the repository.
  See 1.7. This is the layout section describing target-state
  structure, not current structure, without saying so explicitly --
  worth a one-line disclaimer ("not all paths below exist yet") if the
  document is meant to reflect the repository as it stands today.

### 3.3 Dependencies contradicting "standard format libraries allowed"

None found. Every dependency across `pom.xml`, `architecture/pom.xml`,
and `services/identity/pom.xml` is either: (a) a standard third-party
library (Spring Boot, Jackson, protobuf-java, CloudEvents SDK,
json-schema-validator, ArchUnit, JUnit, Mockito, logback, SLF4J), or
(b) `com.iotee.platform:iotee-architecture` at `<scope>test</scope>`
explicitly, the one artifact ADR 0013 permits. No project-authored
runtime library is shared across module boundaries.

### 3.4 Other drift noted

- `.github/workflows/ci.yml`'s top-level `name:` is still
  `M0 foundation gate`, even though the workflow now also runs the
  `java-platform-bootstrap` job for M9's Track B. The workflow's own
  name undersells its current scope -- minor, cosmetic, but a genuine
  mismatch between what the file is called and what it does.
- CLAUDE.md's ADR-0012 milestone bullet still names Track B's
  original, retired shape (`common/` primitives) without flagging that
  ADR 0013 superseded it -- see 1.6.

---

## 4. Reference integrity

Every `docs/adr/` filename CLAUDE.md cites was checked for existence:

| Citation in CLAUDE.md | Path checked | Resolves? |
|---|---|---|
| ADR 0011 | `docs/adr/0011-platform-architecture-and-language-stack.md` | Yes |
| ADR 0012 | `docs/adr/0012-m9-foundation-scope.md` | Yes |
| ADR 0013 | `docs/adr/0013-microservice-autonomy.md` | Yes |
| ADR 0002 (legacy-constraints section) | `docs/adr/0002-migration-studio-foundation.md` | Yes |
| `docs/adr/README.md` | `docs/adr/README.md` | Yes |

"Reading order for a new session" list, checked end to end:

1. `CLAUDE.md` -- exists (self-reference, trivially resolves)
2. `docs/adr/0011-platform-architecture-and-language-stack.md` -- exists
3. `docs/adr/0012-m9-foundation-scope.md` -- exists
4. `docs/adr/0013-microservice-autonomy.md` -- exists, at its **final**
   merged path (not the placeholder path)
5. `docs/adr/README.md` -- exists

**No broken references found.** All five reading-order links resolve
against the current repository state, including the ADR 0013 link,
which now correctly points at the post-rename, post-merge path rather
than the placeholder it was drafted under.

---

## 5. Recommended changes, ranked by leverage

1. **Highest leverage: actually run `mvn -f pom.xml verify` once,
   anywhere with real network access, before trusting any of Section
   1.9's "mechanical" findings.** Every ArchUnit/Enforcer rule in this
   platform is unexercised source code. This is not a CLAUDE.md
   wording problem -- it is the single biggest gap between what the
   document claims is mechanically true and what has been proven. The
   `java-platform-bootstrap` CI job is already configured to do this;
   it just needs the pending commit (`a450156`) to actually reach
   GitHub via a PR.
2. **Push `feature/track-b-java-bootstrap` and open the PR for
   `a450156`.** Until this happens, rule 1.1 (PR/CI/human review) has
   no compliance evidence for the platform's largest change, and rule
   2.2's gate has never run against the current module shape at all.
3. **Move "plug-and-play infrastructure" enforcement from documentary
   to mechanical incrementally, as each piece of infrastructure is
   actually introduced** -- rather than stating a blanket Enforcer/
   ArchUnit guarantee now that only covers the shared-runtime rule.
   Concretely: when the first real TB CE/Kafka/APISIX integration
   lands, add its own banned-SDK-import rule at the same time (the
   `noClassesOutsideSubpackageDependOnPackages` factory in
   `architecture/ArchRules.java` already exists for exactly this
   shape and is currently unused).
4. **ADR update, not just a doc fix: reconcile CLAUDE.md's ADR 0012
   milestone bullet with ADR 0013's retirement of `common/`.** Either
   a short ADR 0013 addendum/erratum noting the CLAUDE.md summary was
   corrected, or simply editing the CLAUDE.md bullet -- the latter is
   lower-ceremony and matches how this repository already treats
   CLAUDE.md as a living summary rather than an ADR itself.
5. **Lower leverage, still worth doing:** rename or re-scope
   `.github/workflows/ci.yml`'s workflow `name:` field now that it
   covers both the Python and Java gates (e.g. "Foundation and
   platform gates"), and add a one-line disclaimer to CLAUDE.md's
   "Repository layout" section that `adapters/` and `deploy/` are
   target paths, not yet present.
6. **Not urgent, but flagged per the constraints of this audit:**
   several constitutional rules (firmware signing, mTLS/JWT/SSO,
   destructive-action approval, infra-provisioning restrictions) are
   currently true only because there is no platform code yet capable
   of violating them. None of these should be read as "passing" in the
   sense of having been tested -- they are unexercised, not verified.
   Re-run an equivalent audit once `services/identity` gains
   persistence, and again once a second service or the first real
   infrastructure adapter lands, since that is when several of these
   rules get their first real chance to be violated.

---

## Summary

- **Documented and followed, with real mechanical enforcement that has
  actually executed and passed:** the M0-M8 Python legacy constraints
  (`spec/scripts/check.py`, 437/437 green), the "blocked not passed"
  discipline (`test_adapters_worm_s3_live.py`'s skip), correlation-ID
  propagation (real code + real test, unexecuted only because `mvn`
  cannot run here).
- **Documented and followed, but only as human process, never checked
  by a machine:** PR/CI/human review, the ADR numbering convention,
  the stage-completion checklist.
- **Documented and structurally correct, but never actually
  exercised/compiled:** every ADR 0013 Java enforcement mechanism --
  the Enforcer `bannedDependencies` rule, the ArchUnit autonomy
  tripwire, the Spring-freedom rules, the Protobuf per-service
  generation. This is the audit's central finding: these look correct
  on inspection and may well be correct, but "correct" here means "the
  author reasoned carefully," not "a tool confirmed it," because no
  tool has run yet.
- **Purely aspirational, with no code to check against either way:**
  plug-and-play infrastructure swappability, firmware signing, TB CE
  export/shadow-comparison in the Java platform, mTLS/JWT/SSO,
  database independence (vacuously true, nothing persists anything
  yet), the `adapters/` and `deploy/` layout entries.
- **No violations found** against any rule that currently has code or
  process to check it against.
