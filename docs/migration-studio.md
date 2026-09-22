# Migration Studio — M1 foundation

## Milestone
M1 (Migration Studio foundation), per `docs/requirements-addendum.md` and
the M1 row of `docs/test-plan.md`.

## Bounded context
Cross-cutting tooling, not one of the eleven business bounded contexts
listed in `docs/architecture.md` — it exists to support migration from the
legacy system into those contexts later, and owns no runtime business data
itself.

## Verified inputs
- `docs/requirements-addendum.md` (uploaded requirements addendum) —
  "Interfaces and migration sequence" and "Vault support" sections.
- `docs/test-plan.md` — M1 gate row: "Source registration/authorization,
  vault write/reference-only persistence, secret redaction, dry-run
  non-mutation, approval transitions and evidence; provider integration
  tests in isolated test environment."
- `CLAUDE.md` — data-handling and PR/CI/human-review rules.
- `docs/inputs.md` — recorded gaps (no approved source owners/access
  scopes, no vault provider decision, etc. are still open "before M1"
  items). This foundation does not resolve those; it builds the code shape
  that will consume them once resolved.

## What is implemented
- `migration_studio/vault.py` — provider-neutral `SecretReference` +
  `VaultProvider` abstraction, capability-declaring, with an in-memory test
  double.
- `migration_studio/sources.py` — source-registration configuration
  lifecycle: register → validate (dry run) → preview (dry run) → approve /
  reject, plus roll back from approved. Raw secret-looking values are
  refused at registration time.
- `migration_studio/evidence.py` — append-only, hash-chained evidence log
  recording every lifecycle transition with a redacted configuration
  snapshot.
- `fixtures/migration_sources.synthetic.json` — synthetic source
  registrations (Mosquitto mirror, PostgreSQL history, ThingsBoard export)
  used by both the unit tests and the M1 gate.
- Gate extension in `scripts/check.py`: required-artifact checks, a static
  policy check that rejects network/process-capable imports anywhere in
  `migration_studio/`, and synthetic-fixture validation.

## Acceptance criteria (traced to the M1 gate row)
| Gate requirement | How it is met |
| --- | --- |
| Source registration/authorization | `SourceRegistry.register`; duplicate/invalid input rejected with tests |
| Vault write/reference-only persistence | `VaultProvider.store_secret` returns only `SecretReference`, never the raw value |
| Secret redaction | `sources._redact` + evidence tests assert raw values never appear in recorded snapshots |
| Dry-run non-mutation | `validate`/`preview` proven not to mutate `config` (`test_validate_is_dry_run_and_does_not_mutate_config`) |
| Approval transitions and evidence | Explicit transition allow-list + hash-chained `EvidenceLog`, with negative tests for disallowed transitions |
| Provider integration tests in isolated test environment | **Blocked, not passed** — no real Vault/AWS/Azure/GCP instance is available in this environment; only the in-memory test double is exercised. This is a real gap, not a check to skip silently. |

## Security constraints observed
- No raw secret value is ever logged, stored outside the (test-only)
  in-memory provider, or written to evidence.
- No network, subprocess, or legacy-system I/O anywhere in this module —
  mechanically enforced by `scripts/check.py`'s import-scanning policy
  check, not just by review.
- No path to an "applied"/cutover state exists in the state machine —
  full production import/cutover is explicitly M9.

## Intended files
`migration_studio/{__init__,vault,sources,evidence}.py`,
`fixtures/migration_sources.synthetic.json`,
`tests/test_migration_studio_{vault,sources,evidence}.py`,
`docs/adr/0002-migration-studio-foundation.md`, this file, and the
extension to `scripts/check.py`.

## Relevant tests
`tests/test_migration_studio_vault.py`,
`tests/test_migration_studio_sources.py`,
`tests/test_migration_studio_evidence.py`, all run by `python scripts/check.py`.

## Non-goals (explicitly out of scope for this change)
- Any real vault/cloud backend integration.
- Reading or connecting to the legacy Mosquitto/PostgreSQL/ThingsBoard
  system in any way.
- Field/formula mapping, shadow comparison, golden-rule parity (M3).
- Inventory of actual legacy rule chains/dashboards/devices (M3/M9).
- Any "controlled application" or cutover step (M9).
- Choosing the real vault provider or identity integration (still an open
  item per `docs/inputs.md`, "Before M1").
