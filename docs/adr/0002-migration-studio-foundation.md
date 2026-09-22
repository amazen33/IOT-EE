# ADR 0002 — Migration Studio foundation: vault abstraction and configuration lifecycle only

Status: accepted for planning, 2026-09-22. Implementation is milestone-gated,
same discipline as ADR 0001.

## Context

The requirements addendum (`docs/requirements-addendum.md`, "Interfaces and
migration sequence") splits Migration Studio work explicitly across stages:
foundation in M1, configurable connectors and shadow-parity tooling in M3,
and full production legacy import/reconciliation/cutover only at M9, after
Deployment Studio (M8). It also requires (`docs/requirements-addendum.md`,
"Vault support") a provider-neutral secret-reference interface, scoped
access, and a strict rule that secret values never reach Git, application
databases, prompts, events, logs, or evidence.

M0 already established the discipline this ADR follows: mechanically enforce
non-goals in the gate script rather than relying on convention (ADR 0001).

## Decision

1. **`migration_studio.vault`** implements only the reference abstraction:
   `SecretReference` (opaque; never carries a value) and `VaultProvider`
   (abstract, capability-declaring). `InMemoryVaultProvider` is a test
   double standing in for the "isolated test environment" the M1 gate
   requires (`docs/test-plan.md`) — it is explicitly documented as not a
   production backend. No HashiCorp Vault client, no Spring Vault
   integration, no AWS/Azure/GCP adapter exists yet; those need their own
   integration tests per the addendum and are out of scope here.
2. **`migration_studio.sources`** implements the configuration lifecycle —
   register, validate (dry run), preview (dry run), approve, reject, roll
   back — as an explicit transition allow-list. There is deliberately no
   "applied"/cutover state: reaching one is impossible by construction
   (`SourceRegistry` and `RegistrationStatus` have no such state), enforced
   by `test_no_application_or_cutover_capability_exists`. Any config field
   whose key looks like a secret must already be a `SecretReference`, or
   registration raises — this is how "never automatically import secrets"
   and "no raw secret values in configuration payloads" are made
   mechanical rather than aspirational.
3. **`migration_studio.evidence`** implements an in-memory, hash-chained,
   append-only log recording every lifecycle transition with a redacted
   configuration snapshot. It is explicitly not the target WORM evidence
   store (`docs/architecture.md`, "Firmware, evidence, and monetization")
   — no persistence, no WORM object storage, no retention/legal-hold
   controls. Those need explicit design and verification at M6.
4. **The M1 gate extension in `scripts/check.py`** adds: required-artifact
   checks for the new module/docs/fixtures/tests; a policy check that
   statically rejects any network/process-capable import
   (`socket`, `requests`, `subprocess`, `psycopg2`, `kafka`, etc.) anywhere
   under `migration_studio/`, so "no legacy system connections" and "no
   network I/O" cannot silently regress; and synthetic-fixture validation
   for the new source-registration fixture, mirroring the M0 telemetry
   fixture check. All M0 checks continue to run unchanged (cumulative
   regression gate).

## Consequences

- A future PR that tries to add a real vault client call, a live MQTT/
  PostgreSQL/ThingsBoard connection, or an "applied" state inside
  `migration_studio` fails the M1 gate immediately rather than at review
  time.
- M3's configurable connectors/shadow-parity tooling and M9's full
  migration/cutover build on this module's types (`SecretReference`,
  `SourceRegistration`, `EvidenceLog`) without needing to redefine the
  lifecycle contract.
- Provider integration tests against a real Vault instance are explicitly
  **blocked, not passed**, in this stage: no such instance is available in
  this environment. `docs/migration-studio.md` records this as a known gap
  for whoever stands up the M1 provider-integration test environment.

## Non-goals (unchanged from the addendum)

- Any real backend integration (Vault/AWS/Azure/GCP).
- Reading or writing anything in a legacy Mosquitto/PostgreSQL/ThingsBoard
  system.
- Field/formula mapping, shadow comparison, or golden-rule parity (M3).
- Any "controlled application" or cutover step (M9).
