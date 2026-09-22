# Reporting — M4

## Milestone
M4, per `docs/requirements-addendum.md`'s delivery table and
`docs/test-plan.md`'s gate row: "...reconciled certified reports and
publication approval." This document covers the reporting-read-model
half; see `docs/gateway.md` for the gateway auth/route contract half.

## Bounded contexts touched
reporting (new).

## Verified inputs
Decisions supplied by the repository owner for this stage (see ADR 0005
for full rationale):
- First report definition: tank level/telemetry summary per tenant.
- Timezone/refill semantics: display in tenant-configured timezone, UTC
  storage and computation unchanged.

Still open per `docs/inputs.md` and explicitly **not** addressed by this
stage: report approvers (who is authorized to approve, versus merely
publish, a report), sanitized expected results for a real approval
workflow, additional report definitions beyond the tank telemetry
summary, accessible design requirements for however this report is
eventually displayed.

## What is implemented
- `reporting/telemetry_summary.py` — `TankTelemetrySummary`,
  `TelemetrySummaryProjector` (a CQRS read-model projector consuming
  `ingestion.outbox.RawTelemetryRecord` rows, idempotent per
  `(tenant_id, event_id)`, tracking latest/min/max level, latest
  temperature/battery, and a consumption-rate-percent-per-hour
  calculation). **Storage: SQLite** (stdlib `sqlite3`, in-memory by
  default) with a real `CREATE TABLE` schema and SQL-level idempotency
  (`ON CONFLICT ... DO UPDATE`, a `processed_telemetry_events` dedup
  table) — chosen over a plain in-memory dict so the read-model's schema
  and query shape are exercised now, closer to the eventual PostgreSQL
  table, without provisioning any real database. This is the one M4
  module where persistence is modeled at all; every other new package
  (`gateway`) stays pure logic, same as `domain_core` and
  `migration_studio`.
- `reporting/timezone.py` — `to_tenant_local` (UTC -> tenant IANA
  timezone display formatting only; storage/computation stay UTC). Backed
  by stdlib `zoneinfo` plus the `tzdata` package (see ADR 0005 decision 5
  and `requirements.txt`) — required because Windows (including GitHub
  Actions' `windows-latest` runner) ships no system IANA database for
  `zoneinfo` to fall back on.
- `reporting/publication.py` — `generate_tank_telemetry_report`
  (tenant/permission-gated report generation via `gateway.auth`),
  `ReportRow`, `publish_report` (a `reports.publish`-gated action that
  fingerprints published rows with a SHA-256 content hash), `PublishedReport`.
- `fixtures/reporting_telemetry.synthetic.json` — synthetic multi-reading,
  multi-tenant telemetry stream, including a duplicated `event_id` to
  exercise the projector's idempotency, with expected per-device summaries
  asserted end-to-end in `tests/test_reporting_telemetry_summary.py::SyntheticFixtureTests`.

## What is explicitly blocked (not passed, not silently skipped)
- **Consuming a real Kafka topic.** The projector consumes
  `ingestion.outbox.RawTelemetryRecord` directly rather than a
  `ingestion.kafka.DeliveredEvent`, because `OutboxEvent.payload` does not
  carry `device_id`/`occurred_at` (see `reporting/telemetry_summary.py`'s
  module docstring and ADR 0005). Wiring this as a genuine downstream
  Kafka consumer requires enriching the published event envelope — real,
  useful follow-up work, not implemented here.
- **A materialized PostgreSQL read-model table.** `TelemetrySummaryProjector`
  is backed by SQLite's embedded engine (in-memory by default, or a local
  file if a path is passed — not exercised by this milestone's tests),
  never a client/server database connection. Migrating the same schema
  shape to a real PostgreSQL read-model table is real follow-up work, not
  done here.
- **A real report-approval workflow.** `publish_report` checks a single
  `reports.publish` permission; no multi-party approval, no named report
  approvers (still an open input per `docs/inputs.md`), and no
  reconciliation against a second data source.
- **Immutable, tamper-evident publication evidence.** `PublishedReport`'s
  content hash is a fingerprint only; it is not written to
  `migration_studio.evidence.EvidenceLog`'s hash-chained log. That
  integration is deferred to M6 ("immutable evidence/resilience/DR" per
  CLAUDE.md's milestone list).
- **Additional report definitions.** Only the tank telemetry summary is
  built this milestone.

These are documented here as blocked, per the contract's requirement to
record unavailable external verification as blocked rather than passed.

## Acceptance criteria (traced to the M4 gate row in docs/test-plan.md)
| Gate requirement | How it is met |
| --- | --- |
| Reconciled certified reports | Partial: `TelemetrySummaryProjector` is deterministic and idempotent given the same input stream (no reconciliation against a second source yet, see blocked list) |
| Publication approval | `publish_report` requires the distinct `reports.publish` permission (not just `reports.view`) and records who published, when, and a content fingerprint |
| Timezone-correct display | `tests/test_reporting_timezone.py` covers positive/negative-offset zones, UTC, and invalid-zone/invalid-timestamp rejection |
| Tenant isolation in reports | `tests/test_reporting_telemetry_summary.py::test_tenants_are_isolated_in_the_projection`; `tests/test_reporting_publication.py::test_requires_matching_tenant` / `test_publish_requires_matching_tenant` |

## Security constraints observed
- `generate_tank_telemetry_report` and `publish_report` both call
  `gateway.auth.require_tenant_match` and `require_permission` before
  touching any data — a session cannot read or publish another tenant's
  report, and viewing does not imply publishing (two distinct permissions).
- The read-model itself is scoped by `(tenant_id, device_id)` internally,
  so even a caller that bypassed the auth checks could not cross-contaminate
  one tenant's summary with another's.
- Report content hashing uses a canonical (sorted-key) JSON serialization,
  so the same logical rows always fingerprint identically regardless of
  dict ordering — a prerequisite for any future reconciliation check.

## Intended files
`reporting/{__init__,telemetry_summary,timezone,publication}.py`,
`fixtures/reporting_telemetry.synthetic.json`,
`tests/test_reporting_{telemetry_summary,timezone,publication}.py`,
`docs/adr/0005-m4-gateway-reporting.md`, this file, and the extension to
`scripts/check.py`.

## Relevant tests
`tests/test_reporting_telemetry_summary.py`, `tests/test_reporting_timezone.py`,
`tests/test_reporting_publication.py`, run by `python scripts/check.py`.

## Non-goals (explicitly out of scope for this change)
- Any real database, Kafka consumer wiring, or evidence-log integration
  (all blocked, see above).
- Additional report definitions beyond tank telemetry summary.
- Named report approvers or a multi-party approval workflow.
- Accessibility, SSR rendering, or WebSocket delivery of this report
  (see `docs/gateway.md`).
- Any infrastructure provisioning.
