import unittest

from domain_core.rbac import Principal, PrincipalKind
from domain_core.units import UtcTimestamp
from gateway.auth import (
    AuthenticationError,
    InMemoryAuthProvider,
    PermissionDeniedError,
    SessionExpiredError,
    TenantMismatchError,
    require_permission,
    require_tenant_match,
)


def _operator(tenant_id="synthetic-tenant-a", permissions=frozenset({"reports.view"})):
    return Principal(
        principal_id="synthetic-operator-001",
        kind=PrincipalKind.TENANT_OPERATOR,
        tenant_id=tenant_id,
        permissions=permissions,
    )


def _system_admin():
    return Principal(principal_id="synthetic-admin-001", kind=PrincipalKind.SYSTEM_ADMIN, tenant_id=None)


class InMemoryAuthProviderTests(unittest.TestCase):
    def test_register_and_authenticate_round_trips(self):
        provider = InMemoryAuthProvider()
        principal = _operator()
        now = UtcTimestamp("2026-01-01T00:00:00Z")
        registered = provider.register_token("synthetic-token-001", principal, issued_at=now, ttl_seconds=3600)

        session = provider.authenticate("synthetic-token-001", now=now)

        self.assertEqual(session, registered)
        self.assertEqual(session.principal, principal)

    def test_unknown_token_raises_authentication_error(self):
        provider = InMemoryAuthProvider()
        with self.assertRaises(AuthenticationError):
            provider.authenticate("synthetic-token-unknown", now=UtcTimestamp("2026-01-01T00:00:00Z"))

    def test_expired_session_raises_session_expired_error(self):
        provider = InMemoryAuthProvider()
        issued_at = UtcTimestamp("2026-01-01T00:00:00Z")
        provider.register_token("synthetic-token-001", _operator(), issued_at=issued_at, ttl_seconds=60)

        with self.assertRaises(SessionExpiredError):
            provider.authenticate("synthetic-token-001", now=UtcTimestamp("2026-01-01T00:05:00Z"))

    def test_ttl_must_be_positive(self):
        provider = InMemoryAuthProvider()
        with self.assertRaises(ValueError):
            provider.register_token(
                "synthetic-token-001", _operator(), issued_at=UtcTimestamp("2026-01-01T00:00:00Z"), ttl_seconds=0
            )

    def test_session_is_expired_boundary_is_inclusive(self):
        provider = InMemoryAuthProvider()
        issued_at = UtcTimestamp("2026-01-01T00:00:00Z")
        session = provider.register_token("synthetic-token-001", _operator(), issued_at=issued_at, ttl_seconds=60)
        self.assertTrue(session.is_expired(now=UtcTimestamp("2026-01-01T00:01:00Z")))
        self.assertFalse(session.is_expired(now=UtcTimestamp("2026-01-01T00:00:59Z")))


class TenantMatchAndPermissionTests(unittest.TestCase):
    def setUp(self):
        self.provider = InMemoryAuthProvider()
        self.now = UtcTimestamp("2026-01-01T00:00:00Z")

    def _session_for(self, principal):
        return self.provider.register_token("synthetic-token-001", principal, issued_at=self.now, ttl_seconds=3600)

    def test_matching_tenant_passes(self):
        session = self._session_for(_operator(tenant_id="synthetic-tenant-a"))
        require_tenant_match(session, "synthetic-tenant-a")  # must not raise

    def test_mismatched_tenant_raises(self):
        session = self._session_for(_operator(tenant_id="synthetic-tenant-a"))
        with self.assertRaises(TenantMismatchError):
            require_tenant_match(session, "synthetic-tenant-b")

    def test_system_admin_bypasses_tenant_match(self):
        session = self._session_for(_system_admin())
        require_tenant_match(session, "synthetic-tenant-a")  # must not raise
        require_tenant_match(session, "synthetic-tenant-b")  # must not raise

    def test_permission_present_passes(self):
        session = self._session_for(_operator(permissions=frozenset({"reports.view"})))
        require_permission(session, "reports.view")  # must not raise

    def test_permission_missing_raises(self):
        session = self._session_for(_operator(permissions=frozenset({"reports.view"})))
        with self.assertRaises(PermissionDeniedError):
            require_permission(session, "reports.publish")

    def test_system_admin_has_every_catalog_permission(self):
        session = self._session_for(_system_admin())
        require_permission(session, "reports.publish")  # must not raise


if __name__ == "__main__":
    unittest.main()
