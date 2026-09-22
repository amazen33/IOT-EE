"""Identity/tenant RBAC: tenant-defined roles built from a fixed permission
catalog, with system-admin / tenant-admin / tenant-operator principal
kinds (requirements-addendum.md, "Consoles and identity").

Decision (docs/adr/0003-m2-domain-core.md): fully custom RBAC — tenant
admins may define arbitrary roles — but every role is a subset of a fixed,
versioned permission catalog. This keeps "custom roles" from becoming "any
string is a permission": authorization checks stay a simple set
membership test, and the catalog is the one place new capabilities get
added as later milestones need them.

Enforced invariants (requirements-addendum.md, "Tenant operators and
secure device property updates"):
- Tenant admins cannot grant beyond their own authority (no self-escalation).
- No cross-tenant delegation.
- System-admin access is separately privileged; operator tokens must never
  carry system-admin or tenant-admin permissions.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
import re

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")

# Fixed, versioned permission catalog. Roles can only be built from these.
# Extend this set (never repurpose an existing string's meaning) as later
# milestones add capabilities.
PERMISSION_CATALOG = frozenset(
    {
        "tenant.manage_roles",
        "tenant.manage_users",
        "tenant.manage_entitlements",
        "device.view",
        "device.command.dispatch",
        "device.command.dispatch.firmware",
        "asset.manage",
        "asset.view",
        "reports.view",
        "reports.publish",
        "rules.manage",
        "firmware.manage",
        "evidence.legal_hold",
    }
)


class PrincipalKind(str, Enum):
    SYSTEM_ADMIN = "system_admin"
    TENANT_ADMIN = "tenant_admin"
    TENANT_OPERATOR = "tenant_operator"


class RbacError(ValueError):
    pass


class SelfEscalationError(RbacError):
    pass


class CrossTenantDelegationError(RbacError):
    pass


@dataclass(frozen=True)
class Role:
    role_id: str
    tenant_id: str
    name: str
    permissions: frozenset[str]

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.role_id):
            raise ValueError("Invalid role_id")
        if not _ID_PATTERN.fullmatch(self.tenant_id):
            raise ValueError("Invalid tenant_id")
        if not self.name:
            raise ValueError("name is required")
        unknown = set(self.permissions) - PERMISSION_CATALOG
        if unknown:
            raise ValueError(f"Unknown permission(s) not in catalog: {sorted(unknown)}")


@dataclass(frozen=True)
class Principal:
    """An authenticated actor. ``tenant_id`` is None only for a
    system-admin principal — per the addendum, admin and operator
    authentication have separate entry points/audiences, so a
    tenant-scoped principal always carries exactly one tenant_id and a
    system-admin principal never does."""

    principal_id: str
    kind: PrincipalKind
    tenant_id: str | None
    permissions: frozenset[str] = field(default_factory=frozenset)

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.principal_id):
            raise ValueError("Invalid principal_id")
        if not isinstance(self.kind, PrincipalKind):
            raise ValueError("Invalid principal kind")
        if self.kind is PrincipalKind.SYSTEM_ADMIN:
            if self.tenant_id is not None:
                raise ValueError("System-admin principals must not carry a tenant_id")
        else:
            if not self.tenant_id or not _ID_PATTERN.fullmatch(self.tenant_id):
                raise ValueError("Tenant-scoped principals require a valid tenant_id")
        unknown = set(self.permissions) - PERMISSION_CATALOG
        if unknown:
            raise ValueError(f"Unknown permission(s) not in catalog: {sorted(unknown)}")

    def has_permission(self, permission: str) -> bool:
        if permission not in PERMISSION_CATALOG:
            raise ValueError(f"'{permission}' is not in the permission catalog")
        return self.kind is PrincipalKind.SYSTEM_ADMIN or permission in self.permissions


@dataclass(frozen=True)
class RoleAssignment:
    principal_id: str
    tenant_id: str
    role_id: str


def create_role(*, assigner: Principal, role_id: str, tenant_id: str, name: str, permissions: frozenset[str]) -> Role:
    """Define a new tenant role. Only a system-admin, or a tenant-admin
    acting within their own tenant and granting no permission they do not
    themselves hold, may do this."""
    role = Role(role_id=role_id, tenant_id=tenant_id, name=name, permissions=frozenset(permissions))
    _authorize_grant(assigner, role)
    return role


def assign_role(*, assigner: Principal, assignee_principal_id: str, role: Role) -> RoleAssignment:
    """Grant ``role`` to ``assignee_principal_id``. Enforces the same
    no-self-escalation / no-cross-tenant-delegation rule as ``create_role``,
    plus rejecting a system-admin-only role being handed to a tenant-scoped
    assignee (there is no such role: system-admin is a principal kind, not
    a grantable permission set, so this is enforced structurally by Role
    always carrying a tenant_id)."""
    _authorize_grant(assigner, role)
    return RoleAssignment(principal_id=assignee_principal_id, tenant_id=role.tenant_id, role_id=role.role_id)


def _authorize_grant(assigner: Principal, role: Role) -> None:
    if assigner.kind is PrincipalKind.SYSTEM_ADMIN:
        return
    if assigner.tenant_id != role.tenant_id:
        raise CrossTenantDelegationError(
            f"Principal in tenant '{assigner.tenant_id}' cannot act on tenant '{role.tenant_id}'"
        )
    missing = set(role.permissions) - set(assigner.permissions)
    if missing:
        raise SelfEscalationError(
            f"Cannot grant permission(s) the assigner does not hold: {sorted(missing)}"
        )
    if "tenant.manage_roles" not in assigner.permissions:
        raise RbacError("Assigner lacks tenant.manage_roles")
