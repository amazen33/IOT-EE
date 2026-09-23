"""reporting bounded context: report generation and publication approval.

Decision (docs/adr/0005-m4-gateway-reporting.md): "reconciled certified
reports and publication approval" (docs/test-plan.md's M4 gate row) is
modeled here as a permission-gated publish action that fingerprints the
published rows -- not a full reconciliation/certification workflow or an
immutable audit trail. Wiring publication into the hash-chained evidence
log (migration_studio.evidence.EvidenceLog, built in M1) is real, useful
future work but is explicitly deferred to M6 ("immutable
evidence/resilience/DR" per CLAUDE.md's milestone list), not implemented
here -- see docs/reporting.md.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

from gateway.auth import AuthenticatedSession, require_permission, require_tenant_match
from domain_core.units import UtcTimestamp
from reporting.telemetry_summary import TankTelemetrySummary, TelemetrySummaryProjector
from reporting.timezone import to_tenant_local


@dataclass(frozen=True)
class ReportRow:
    device_id: str
    latest_level_percent: float
    min_level_percent: float
    max_level_percent: float
    latest_temperature_c: float
    latest_battery_percent: float
    consumption_rate_percent_per_hour: float | None
    last_reading_at_local: str


@dataclass(frozen=True)
class PublishedReport:
    report_id: str
    tenant_id: str
    published_by: str
    published_at: UtcTimestamp
    row_count: int
    content_hash: str


def _to_row(summary: TankTelemetrySummary, *, viewer_timezone: str) -> ReportRow:
    return ReportRow(
        device_id=summary.device_id,
        latest_level_percent=summary.latest_level_percent,
        min_level_percent=summary.min_level_percent,
        max_level_percent=summary.max_level_percent,
        latest_temperature_c=summary.latest_temperature_c,
        latest_battery_percent=summary.latest_battery_percent,
        consumption_rate_percent_per_hour=summary.consumption_rate_percent_per_hour,
        last_reading_at_local=to_tenant_local(summary.last_reading_at, viewer_timezone),
    )


def generate_tank_telemetry_report(
    projector: TelemetrySummaryProjector,
    *,
    session: AuthenticatedSession,
    tenant_id: str,
    viewer_timezone: str,
) -> list[ReportRow]:
    """Requires the session to belong to (or admin over) ``tenant_id`` and
    hold ``reports.view`` -- the same gateway-boundary checks
    ``gateway.routes``' reports-tank-summary-view route contract names."""
    require_tenant_match(session, tenant_id)
    require_permission(session, "reports.view")
    summaries = sorted(projector.summaries_for_tenant(tenant_id), key=lambda summary: summary.device_id)
    return [_to_row(summary, viewer_timezone=viewer_timezone) for summary in summaries]


def _content_hash(rows: list[ReportRow]) -> str:
    canonical = json.dumps([row.__dict__ for row in rows], sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def publish_report(
    *,
    session: AuthenticatedSession,
    tenant_id: str,
    report_id: str,
    rows: list[ReportRow],
    now: UtcTimestamp,
) -> PublishedReport:
    """Requires ``reports.publish`` (a distinct, stronger permission from
    ``reports.view``) and the same tenant scoping. Returns a fingerprinted
    publication record; it does not write to any durable, tamper-evident
    log -- that integration is explicitly deferred, see module docstring.
    """
    require_tenant_match(session, tenant_id)
    require_permission(session, "reports.publish")
    return PublishedReport(
        report_id=report_id,
        tenant_id=tenant_id,
        published_by=session.principal.principal_id,
        published_at=now,
        row_count=len(rows),
        content_hash=_content_hash(rows),
    )
