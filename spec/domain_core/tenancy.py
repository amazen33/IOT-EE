"""Tenant identity and the hybrid multi-tenant isolation decision.

Decision (docs/adr/0003-m2-domain-core.md): hybrid isolation — large
tenants get a dedicated PostgreSQL schema, small tenants share one schema
with row-level security. This module models the *decision* and its
invariants (which mode a tenant is in, and a valid schema name when
schema-per-tenant applies). It does not create, migrate, or connect to any
real PostgreSQL schema — that is M3+ infrastructure work; M2 is domain
core only, and M0's "no infrastructure provisioning" discipline still
applies to every context until its integration milestone.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import re

_TENANT_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,64}")
_SCHEMA_NAME_PATTERN = re.compile(r"[a-z][a-z0-9_]{0,62}")

# Threshold is a domain policy, not a database concern: it decides which
# isolation mode a tenant is assigned, nothing about how that mode is
# realized in infrastructure.
_LARGE_TENANT_DEVICE_THRESHOLD = 1000


class TenantSizeTier(str, Enum):
    STANDARD = "standard"
    ENTERPRISE = "enterprise"


class IsolationMode(str, Enum):
    SCHEMA_PER_TENANT = "schema_per_tenant"
    SHARED_RLS = "shared_rls"


def choose_isolation_mode(tier: TenantSizeTier, *, provisioned_device_count: int = 0) -> IsolationMode:
    """Hybrid rule: enterprise-tier tenants, or any tenant that has grown
    past the shared-schema device threshold, get schema-per-tenant.
    Everyone else shares one schema with row-level security.
    """
    if not isinstance(tier, TenantSizeTier):
        raise ValueError("Invalid tenant size tier")
    if provisioned_device_count < 0:
        raise ValueError("provisioned_device_count cannot be negative")
    if tier is TenantSizeTier.ENTERPRISE or provisioned_device_count > _LARGE_TENANT_DEVICE_THRESHOLD:
        return IsolationMode.SCHEMA_PER_TENANT
    return IsolationMode.SHARED_RLS


def derive_schema_name(tenant_id: str) -> str:
    """A safe PostgreSQL schema identifier for a schema-per-tenant tenant.
    Pure string derivation; does not touch a database."""
    if not _TENANT_ID_PATTERN.fullmatch(tenant_id):
        raise ValueError("Invalid tenant_id")
    candidate = f"tenant_{tenant_id}".lower().replace("-", "_")
    if not _SCHEMA_NAME_PATTERN.fullmatch(candidate):
        raise ValueError(f"tenant_id '{tenant_id}' does not derive a valid schema name")
    return candidate


@dataclass(frozen=True)
class Tenant:
    tenant_id: str
    name: str
    tier: TenantSizeTier
    provisioned_device_count: int = 0

    def __post_init__(self) -> None:
        if not _TENANT_ID_PATTERN.fullmatch(self.tenant_id):
            raise ValueError("Invalid tenant_id")
        if not self.name:
            raise ValueError("name is required")
        if not isinstance(self.tier, TenantSizeTier):
            raise ValueError("Invalid tier")
        if self.provisioned_device_count < 0:
            raise ValueError("provisioned_device_count cannot be negative")

    @property
    def isolation_mode(self) -> IsolationMode:
        return choose_isolation_mode(self.tier, provisioned_device_count=self.provisioned_device_count)

    @property
    def schema_name(self) -> str | None:
        """Only meaningful for schema-per-tenant tenants; None for
        shared_rls tenants (they live in the shared schema, not one of
        their own)."""
        if self.isolation_mode is IsolationMode.SCHEMA_PER_TENANT:
            return derive_schema_name(self.tenant_id)
        return None
