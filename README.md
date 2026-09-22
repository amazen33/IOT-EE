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
- M5 onward: not started.
