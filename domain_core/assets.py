"""asset-tank bounded context: hierarchy (region/site/tank) and relations.

Domain core only (M2): in-memory entities and invariants. No persistence,
no PostgreSQL schema, no legacy import. Tank material/communication-type
metadata and geometry/formula details are recorded as opaque fields here;
the formulas themselves (docs/inputs.md, "Before M2-M3": "tank
geometry/material/formulas") are still an open input and are not
implemented — a Tank's ``geometry`` field is a place to attach that later,
validated for shape only, not computed against.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
import re

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")


class TankMaterial(str, Enum):
    STEEL = "steel"
    FIBERGLASS = "fiberglass"
    CONCRETE = "concrete"
    OTHER = "other"


class CommunicationType(str, Enum):
    CELLULAR = "cellular"
    LORAWAN = "lorawan"
    WIFI = "wifi"
    SATELLITE = "satellite"


class CrossTenantRelationError(ValueError):
    pass


@dataclass(frozen=True)
class Region:
    region_id: str
    tenant_id: str
    name: str

    def __post_init__(self) -> None:
        _require_id(self.region_id, "region_id")
        _require_id(self.tenant_id, "tenant_id")
        if not self.name:
            raise ValueError("name is required")


@dataclass(frozen=True)
class Site:
    site_id: str
    tenant_id: str
    region_id: str
    name: str

    def __post_init__(self) -> None:
        _require_id(self.site_id, "site_id")
        _require_id(self.tenant_id, "tenant_id")
        _require_id(self.region_id, "region_id")
        if not self.name:
            raise ValueError("name is required")


@dataclass(frozen=True)
class Tank:
    tank_id: str
    tenant_id: str
    site_id: str
    material: TankMaterial
    communication_type: CommunicationType
    geometry: dict = field(default_factory=dict)

    def __post_init__(self) -> None:
        _require_id(self.tank_id, "tank_id")
        _require_id(self.tenant_id, "tenant_id")
        _require_id(self.site_id, "site_id")
        if not isinstance(self.material, TankMaterial):
            raise ValueError("Invalid material")
        if not isinstance(self.communication_type, CommunicationType):
            raise ValueError("Invalid communication_type")
        if not isinstance(self.geometry, dict):
            raise ValueError("geometry must be a dict")


def _require_id(value: str, field_name: str) -> None:
    if not isinstance(value, str) or not _ID_PATTERN.fullmatch(value):
        raise ValueError(f"Invalid {field_name}")


class AssetHierarchy:
    """Tenant-scoped register of regions/sites/tanks with parent-child
    relations. Refuses any relation that would cross a tenant boundary —
    this is the domain-core enforcement of "cross-context database writes
    are prohibited" applied within a single context's own hierarchy."""

    def __init__(self) -> None:
        self._regions: dict[str, Region] = {}
        self._sites: dict[str, Site] = {}
        self._tanks: dict[str, Tank] = {}

    def add_region(self, region: Region) -> Region:
        if region.region_id in self._regions:
            raise ValueError(f"Region '{region.region_id}' already exists")
        self._regions[region.region_id] = region
        return region

    def add_site(self, site: Site) -> Site:
        region = self._regions.get(site.region_id)
        if region is None:
            raise ValueError(f"Unknown region '{site.region_id}'")
        if region.tenant_id != site.tenant_id:
            raise CrossTenantRelationError(
                f"Site tenant '{site.tenant_id}' does not match region tenant '{region.tenant_id}'"
            )
        if site.site_id in self._sites:
            raise ValueError(f"Site '{site.site_id}' already exists")
        self._sites[site.site_id] = site
        return site

    def add_tank(self, tank: Tank) -> Tank:
        site = self._sites.get(tank.site_id)
        if site is None:
            raise ValueError(f"Unknown site '{tank.site_id}'")
        if site.tenant_id != tank.tenant_id:
            raise CrossTenantRelationError(
                f"Tank tenant '{tank.tenant_id}' does not match site tenant '{site.tenant_id}'"
            )
        if tank.tank_id in self._tanks:
            raise ValueError(f"Tank '{tank.tank_id}' already exists")
        self._tanks[tank.tank_id] = tank
        return tank

    def tanks_for_tenant(self, tenant_id: str) -> list[Tank]:
        return [tank for tank in self._tanks.values() if tank.tenant_id == tenant_id]

    def get_tank(self, tank_id: str) -> Tank:
        return self._tanks[tank_id]
