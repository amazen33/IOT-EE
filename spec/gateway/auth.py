"""gateway bounded context: provider-neutral authentication contract.

Decision (docs/adr/0005-m4-gateway-reporting.md): a real identity provider
is deferred -- this module defines an abstract ``AuthProvider`` (same
provider-neutral shape as ``migration_studio.vault.VaultProvider`` and
``ingestion.kafka.KafkaPublisher``) plus an in-memory test double.
Whatever the eventual IdP is (OIDC/SAML/etc.), it authenticates a bearer
token into a ``domain_core.rbac.Principal`` -- this module never
re-implements RBAC, it only wraps M2's ``Principal`` with a session
(issued/expiry) and enforces tenant/permission checks at the gateway
boundary.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass

from domain_core.rbac import Principal, PrincipalKind
from domain_core.units import UtcTimestamp


class AuthenticationError(PermissionError):
    """Raised when a token cannot be resolved to a valid session at all
    (unknown token). Distinct from an expired session, which is its own
    subclass, so callers can tell "never valid" from "was valid, now
    stale"."""


class SessionExpiredError(AuthenticationError):
    pass


class AuthorizationError(PermissionError):
    """Raised when a session is valid but does not satisfy a required
    tenant scope or permission for the resource being accessed."""


class TenantMismatchError(AuthorizationError):
    pass


class PermissionDeniedError(AuthorizationError):
    pass


@dataclass(frozen=True)
class AuthenticatedSession:
    session_id: str
    principal: Principal
    issued_at: UtcTimestamp
    expires_at: UtcTimestamp

    def is_expired(self, *, now: UtcTimestamp) -> bool:
        return self.expires_at <= now


class AuthProvider(ABC):
    """Provider-neutral token authentication. A real adapter (OIDC
    discovery + JWKS verification, SAML assertion validation, etc.) is
    future integration work with its own tests; this module never
    contacts a real identity provider or verifies a real signature."""

    @abstractmethod
    def authenticate(self, token: str, *, now: UtcTimestamp) -> AuthenticatedSession:
        ...


class InMemoryAuthProvider(AuthProvider):
    """Test double only: a fixed token -> session mapping registered by
    the test itself, standing in for a real IdP's token verification."""

    def __init__(self) -> None:
        self._sessions: dict[str, AuthenticatedSession] = {}

    def register_token(
        self, token: str, principal: Principal, *, issued_at: UtcTimestamp, ttl_seconds: int
    ) -> AuthenticatedSession:
        if ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be positive")
        session = AuthenticatedSession(
            session_id=f"session-{token}",
            principal=principal,
            issued_at=issued_at,
            expires_at=issued_at.add_seconds(ttl_seconds),
        )
        self._sessions[token] = session
        return session

    def authenticate(self, token: str, *, now: UtcTimestamp) -> AuthenticatedSession:
        session = self._sessions.get(token)
        if session is None:
            raise AuthenticationError("Unknown or invalid token")
        if session.is_expired(now=now):
            raise SessionExpiredError(f"Session '{session.session_id}' has expired")
        return session


def require_tenant_match(session: AuthenticatedSession, resource_tenant_id: str) -> None:
    """A system-admin principal may act across tenants (same structural
    exception domain_core.commands.authorize_command carves out for
    direct dispatch is a *rejection* for admins; here, for read-path
    tenant scoping, a system-admin is allowed through -- it is a distinct
    principal kind, never a tenant-scoped role, so this is not a
    cross-tenant leak between tenants, it is the admin plane by design)."""
    principal = session.principal
    if principal.kind is PrincipalKind.SYSTEM_ADMIN:
        return
    if principal.tenant_id != resource_tenant_id:
        raise TenantMismatchError(
            f"Principal in tenant '{principal.tenant_id}' cannot access tenant '{resource_tenant_id}'"
        )


def require_permission(session: AuthenticatedSession, permission: str) -> None:
    if not session.principal.has_permission(permission):
        raise PermissionDeniedError(f"Principal lacks required permission '{permission}'")
