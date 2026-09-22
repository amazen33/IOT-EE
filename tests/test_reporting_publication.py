import unittest

from domain_core.rbac import Principal, PrincipalKind
from domain_core.units import UtcTimestamp
from gateway.auth import InMemoryAuthProvider, PermissionDeniedError, TenantMismatchError
from ingestion.outbox import RawTelemetryRecord
from reporting.publication import generate_tank_telemetry_report, publish_report
from reporting.telemetry_summary import TelemetrySummaryProjector


def _session(provider, *, tenant_id="synthetic-tenant-a", permissions=frozenset({"reports.view"})):
    principal = Principal(
        principal_id="synthetic-operator-001", kind=PrincipalKind.TENANT_OPERATOR,
        tenant_id=tenant_id, permissions=permissions,
    )
    return provider.register_token(
        "synthetic-token-001", principal, issued_at=UtcTimestamp("2026-01-01T00:00:00Z"), ttl_seconds=3600
    )


def _seeded_projector():
    projector = TelemetrySummaryProjector()
    projector.apply(
        RawTelemetryRecord(
            event_id="synthetic-event-001", tenant_id="synthetic-tenant-a", device_id="synthetic-device-001",
            occurred_at="2026-01-01T00:00:00Z",
            payload={"level_percent": 60.0, "temperature_c": 20.0, "battery_percent": 90},
        )
    )
    projector.apply(
        RawTelemetryRecord(
            event_id="synthetic-event-002", tenant_id="synthetic-tenant-a", device_id="synthetic-device-001",
            occurred_at="2026-01-01T01:00:00Z",
            payload={"level_percent": 55.0, "temperature_c": 20.5, "battery_percent": 89},
        )
    )
    return projector


class GenerateTankTelemetryReportTests(unittest.TestCase):
    def setUp(self):
        self.provider = InMemoryAuthProvider()
        self.projector = _seeded_projector()

    def test_generates_a_row_per_device_with_local_display_time(self):
        session = _session(self.provider)
        rows = generate_tank_telemetry_report(
            self.projector, session=session, tenant_id="synthetic-tenant-a", viewer_timezone="UTC"
        )
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].device_id, "synthetic-device-001")
        self.assertEqual(rows[0].last_reading_at_local, "2026-01-01T01:00:00+00:00")
        self.assertAlmostEqual(rows[0].consumption_rate_percent_per_hour, -5.0)

    def test_requires_reports_view_permission(self):
        session = _session(self.provider, permissions=frozenset())
        with self.assertRaises(PermissionDeniedError):
            generate_tank_telemetry_report(
                self.projector, session=session, tenant_id="synthetic-tenant-a", viewer_timezone="UTC"
            )

    def test_requires_matching_tenant(self):
        session = _session(self.provider, tenant_id="synthetic-tenant-a")
        with self.assertRaises(TenantMismatchError):
            generate_tank_telemetry_report(
                self.projector, session=session, tenant_id="synthetic-tenant-b", viewer_timezone="UTC"
            )

    def test_empty_tenant_yields_empty_report(self):
        session = _session(self.provider, tenant_id="synthetic-tenant-c", permissions=frozenset({"reports.view"}))
        rows = generate_tank_telemetry_report(
            self.projector, session=session, tenant_id="synthetic-tenant-c", viewer_timezone="UTC"
        )
        self.assertEqual(rows, [])


class PublishReportTests(unittest.TestCase):
    def setUp(self):
        self.provider = InMemoryAuthProvider()
        self.projector = _seeded_projector()

    def test_publish_requires_reports_publish_not_just_view(self):
        session = _session(self.provider, permissions=frozenset({"reports.view"}))
        rows = generate_tank_telemetry_report(
            self.projector, session=session, tenant_id="synthetic-tenant-a", viewer_timezone="UTC"
        )
        with self.assertRaises(PermissionDeniedError):
            publish_report(
                session=session, tenant_id="synthetic-tenant-a", report_id="synthetic-report-001",
                rows=rows, now=UtcTimestamp("2026-01-01T02:00:00Z"),
            )

    def test_publish_succeeds_with_reports_publish_permission(self):
        session = _session(self.provider, permissions=frozenset({"reports.view", "reports.publish"}))
        rows = generate_tank_telemetry_report(
            self.projector, session=session, tenant_id="synthetic-tenant-a", viewer_timezone="UTC"
        )
        published = publish_report(
            session=session, tenant_id="synthetic-tenant-a", report_id="synthetic-report-001",
            rows=rows, now=UtcTimestamp("2026-01-01T02:00:00Z"),
        )
        self.assertEqual(published.row_count, 1)
        self.assertEqual(published.published_by, "synthetic-operator-001")
        self.assertEqual(len(published.content_hash), 64)

    def test_content_hash_is_deterministic_for_identical_rows(self):
        session = _session(self.provider, permissions=frozenset({"reports.view", "reports.publish"}))
        rows = generate_tank_telemetry_report(
            self.projector, session=session, tenant_id="synthetic-tenant-a", viewer_timezone="UTC"
        )
        now = UtcTimestamp("2026-01-01T02:00:00Z")
        first = publish_report(session=session, tenant_id="synthetic-tenant-a", report_id="r1", rows=rows, now=now)
        second = publish_report(session=session, tenant_id="synthetic-tenant-a", report_id="r2", rows=rows, now=now)
        self.assertEqual(first.content_hash, second.content_hash)

    def test_publish_requires_matching_tenant(self):
        session = _session(self.provider, tenant_id="synthetic-tenant-a", permissions=frozenset({"reports.publish"}))
        with self.assertRaises(TenantMismatchError):
            publish_report(
                session=session, tenant_id="synthetic-tenant-b", report_id="synthetic-report-001",
                rows=[], now=UtcTimestamp("2026-01-01T02:00:00Z"),
            )


if __name__ == "__main__":
    unittest.main()
