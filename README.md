# IOT-EE

M0 foundation, M1 Migration Studio foundation, and M2 domain core (tenancy,
RBAC, asset/device model, command domain). No legacy access, exports, rule
parity, infrastructure, or production implementation has been verified.
Existing root images and Multipass scripts are unverified reference
inputs, preserved unchanged. The scripts provision machines and replace
guest hosts files; they are not part of the supported deployment or test
path and must not be executed by CI.

## Local gate

Use Python 3.11 or newer; no third-party dependencies or network access are
required.

```sh
python scripts/check.py
```

The gate runs the complete current test suite, validates synthetic
fixtures and the inert deployment plan, statically checks that
`migration_studio/` and `domain_core/` perform no network/process I/O, and
checks required stage artifacts. Python is M0-M2 tooling only; the
application language/framework is undecided.

Read [architecture](docs/architecture.md), decisions
([M0](docs/adr/0001-foundation.md), [M1](docs/adr/0002-migration-studio-foundation.md),
[M2](docs/adr/0003-m2-domain-core.md)),
[milestones and test gates](docs/test-plan.md), [missing inputs](docs/inputs.md),
the [Migration Studio foundation write-up](docs/migration-studio.md), and the
[domain core write-up](docs/domain-core.md). Developer instructions are in
[CLAUDE.md](CLAUDE.md).

No remote, branch protection, hosted CI run, container build, or deployment
is implied by local gate success. Configure the repository host and
required review checks before collaborative delivery.

## Status

- **M0 — done**: inert foundation, synthetic telemetry contract, inert
  deployment plan, gate script.
- **M1 — foundation done, provider integration blocked**: vault
  abstraction, source-registration configuration lifecycle, hash-chained
  evidence log. Real provider integration tests are blocked (no isolated
  Vault/cloud environment available here) — see
  [`docs/migration-studio.md`](docs/migration-studio.md).
- **M2 — domain core done**: tenancy (hybrid isolation decision), RBAC
  (custom tenant roles over a fixed permission catalog), asset/tank
  hierarchy, opaque tenant-scoped device identity, and the operator/device
  command domain (authorization, TTL, idempotent dispatch). No real
  PostgreSQL schema, MQTT transport, or device delivery yet — see
  [`docs/domain-core.md`](docs/domain-core.md).
- **M3 — ingestion foundation done, real backends blocked**: MQTT topic
  convention, a transactional outbox (raw_telemetry + outbox_event),
  a Kafka relay with at-least-once-tolerant idempotent consumption,
  bounded per-device offline command queueing with reconnect
  replay-prevention, and a ThingsBoard shadow-parity comparison
  framework exercised against synthetic data only. No real MQTT broker,
  PostgreSQL, Kafka cluster, or ThingsBoard connection exists yet — see
  [`docs/ingestion.md`](docs/ingestion.md).
- **M4 — reporting read-model + gateway auth/route contract done, SSR/WS/real-gateway blocked**:
  a CQRS read-model projecting M3's telemetry into a per-tenant tank
  summary (latest/min/max level, consumption rate), tenant-local display
  formatting (UTC storage/computation unchanged), a permission-gated
  report publication record, and a provider-neutral gateway auth contract
  plus an APISIX-shaped route/policy contract. No real APISIX, identity
  provider, SSR renderer, or WebSocket server exists yet — see
  [`docs/gateway.md`](docs/gateway.md) and [`docs/reporting.md`](docs/reporting.md).
- **M5 — firmware signing/provenance/rollout done, real HSM/transport blocked**:
  provider-neutral signature verification, firmware artifact provenance
  (build metadata + hash + signer identity, verified as a unit), and a
  staged canary-then-broad rollout with automatic rollback to
  last-known-good on a failed device health check. No real HSM/KMS,
  device secure-boot chain, build-system provenance verification, or
  firmware download/flashing transport exists yet — see
  [`docs/firmware.md`](docs/firmware.md).
- **M6 — immutable evidence/legal-hold/replay-reconciliation done, real WORM/DR blocked**:
  a provider-neutral WORM store contract for the audit/compliance trail
  (RBAC decisions, firmware rollout/rollback events, shadow-parity
  comparisons), redaction and content-hash integrity checks on every
  evidence record, an RBAC-gated legal-hold flag that structurally
  blocks retention deletion, and a replay/reconciliation procedure that
  rebuilds a read-model from durable events against documented
  per-tenant RPO/RTO targets. No real WORM/object-lock backend, backup/
  restore execution, cross-region failover, fault injection, or
  load/soak testing exists yet — see
  [`docs/evidence.md`](docs/evidence.md).
- **M7 — optional monetization behind a runtime feature flag done, real payment/tax blocked**:
  a default-off, per-tenant-overridable monetization flag as the single
  gate for all billing side effects; certified (active) device-count
  metering; effective-dated, versioned price plans; immutable period
  closure with reversal-only correction; and a mechanical import-scan
  rule (`scripts/check.py`) proving no other bounded context can ever
  depend on billing. Disabling the flag leaves every other feature fully
  available -- there is no feature-entitlement check anywhere in this
  package. No real payment provider, invoicing, or tax/currency handling
  exists yet -- see [`docs/billing.md`](docs/billing.md).
- **M8 — Deployment Studio/multi-environment done, plan/validate-only, real apply blocked**:
  an immutable, append-only, versioned deployment-profile registry with
  default-deny lookup (closed environment/provider/region enums, prod
  requires approval); an RBAC-gated plan lifecycle (draft, validated,
  approved/rejected) with a required, write-once approval-audit trail;
  structural IaC-document validation that refuses any `apply: true`
  document; a least-privilege runner-policy contract that can never
  declare apply/destroy; and GitOps drift detection between a plan's
  desired state and a supplied observed-state snapshot. No real
  terraform/ansible apply, cloud API call, GitOps controller connection,
  or live infrastructure read exists yet -- see
  [`docs/deployment-studio.md`](docs/deployment-studio.md).
- **Graduation (post-M8) -- a real (test-target) S3 WORM adapter**:
  `adapters.worm_s3.S3WormStore`, a real S3-compatible implementation of
  `evidence.worm.WormStore` using genuine S3 Object Lock for legal hold
  and retention, kept outside every inert core package and wired in only
  as an optional extra (`requirements-adapters-s3.txt`, lazily imported
  -- `requirements.txt` is unchanged). Scoped to a synthetic/test bucket
  only; the one test performing real network I/O skips cleanly (reported
  as skipped, never passed) without `WORM_S3_BUCKET` set. No production
  bucket/region, IAM role design, indexed tenant listing, or
  production retention claim exists yet -- see
  [`docs/adapters-worm-s3.md`](docs/adapters-worm-s3.md).
- All CLAUDE.md milestones (M0-M8) have an initial, contract-gated slice complete;
  each stage's real-backend items remain individually documented as blocked in its own docs page.
  One of M6's blocked items (a real WORM backend) has since been graduated to a
  real test-target implementation -- see the graduation bullet above.
