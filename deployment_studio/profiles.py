"""deployment_studio.profiles: versioned, effective-dated deployment
profiles and their default-deny registry.

Decision (docs/adr/0009-m8-deployment-studio.md): a ``DeploymentProfile``
is never mutated once registered -- correcting one means registering a
new version at a new ``effective_from`` date, same discipline as M7's
``billing.pricing.PricePlanCatalog``. Registration enforces four rules
mechanically:

- **Closed enums.** ``environment``, ``provider``, and ``region`` are all
  drawn from fixed sets, and a region must belong to its provider's
  allowed region set (``_PROVIDER_REGIONS``) -- a profile pairing
  ``ON_PREM`` with an AWS region cannot be constructed.
- **Environment rule: prod requires approval.** A ``PROD`` profile cannot
  be registered unless it already carries ``approved=True`` plus
  ``approved_by``/``approved_at`` -- see ``deployment_studio.plan`` for
  the RBAC-gated approval step that produces an approved profile.
- **Tenant isolation.** Every profile carries a ``tenant_id``; the
  registry's version history and lookups are always scoped to
  ``(tenant_id, profile_id)``, never bare ``profile_id``.
- **Default-deny lookup.** ``ApprovedProfileRegistry.profile_as_of``
  raises ``UnknownProfileError`` for an unregistered or not-yet-effective
  profile -- it never returns ``None`` for "no profile," so a caller
  cannot accidentally treat "unknown" as "use some default."
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_TIMESTAMP_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")


class Environment(str, Enum):
    DEV = "dev"
    STAGING = "staging"
    PROD = "prod"


class Provider(str, Enum):
    ON_PREM = "on_prem"
    AWS = "aws"
    AZURE = "azure"
    GCP = "gcp"


class Region(str, Enum):
    ON_PREM_PRIMARY = "on_prem_primary"
    ON_PREM_DR = "on_prem_dr"
    AWS_US_EAST_1 = "aws_us_east_1"
    AWS_EU_WEST_1 = "aws_eu_west_1"
    AZURE_EASTUS = "azure_eastus"
    GCP_US_CENTRAL1 = "gcp_us_central1"


class Tier(str, Enum):
    STANDARD = "standard"
    HIGH_AVAILABILITY = "high_availability"


_PROVIDER_REGIONS: dict[Provider, frozenset[Region]] = {
    Provider.ON_PREM: frozenset({Region.ON_PREM_PRIMARY, Region.ON_PREM_DR}),
    Provider.AWS: frozenset({Region.AWS_US_EAST_1, Region.AWS_EU_WEST_1}),
    Provider.AZURE: frozenset({Region.AZURE_EASTUS}),
    Provider.GCP: frozenset({Region.GCP_US_CENTRAL1}),
}


class InvalidDeploymentProfileError(ValueError):
    pass


class ProfileApprovalRequiredError(ValueError):
    pass


class DuplicateProfileVersionError(ValueError):
    pass


class UnknownProfileError(KeyError):
    pass


@dataclass(frozen=True)
class DeploymentProfile:
    profile_id: str
    tenant_id: str
    environment: Environment
    provider: Provider
    region: Region
    tier: Tier
    capabilities: frozenset[str]
    effective_from: str
    rpo_seconds: int | None = None
    rto_seconds: int | None = None
    approved: bool = False
    approved_by: str | None = None
    approved_at: str | None = None

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.profile_id):
            raise InvalidDeploymentProfileError("Invalid profile_id")
        if not _ID_PATTERN.fullmatch(self.tenant_id):
            raise InvalidDeploymentProfileError("Invalid tenant_id")
        if not isinstance(self.environment, Environment):
            raise InvalidDeploymentProfileError("Invalid environment")
        if not isinstance(self.provider, Provider):
            raise InvalidDeploymentProfileError("Invalid provider")
        if not isinstance(self.region, Region):
            raise InvalidDeploymentProfileError("Invalid region")
        if self.region not in _PROVIDER_REGIONS[self.provider]:
            raise InvalidDeploymentProfileError(f"Region {self.region} is not valid for provider {self.provider}")
        if not isinstance(self.tier, Tier):
            raise InvalidDeploymentProfileError("Invalid tier")
        if not self.capabilities:
            raise InvalidDeploymentProfileError("capabilities must be non-empty")
        if not _TIMESTAMP_PATTERN.fullmatch(self.effective_from):
            raise InvalidDeploymentProfileError("effective_from must be an ISO-8601 UTC timestamp")
        if self.rpo_seconds is not None and self.rpo_seconds <= 0:
            raise InvalidDeploymentProfileError("rpo_seconds must be positive if given")
        if self.rto_seconds is not None and self.rto_seconds <= 0:
            raise InvalidDeploymentProfileError("rto_seconds must be positive if given")
        if self.environment is Environment.PROD:
            if not (self.approved and self.approved_by and self.approved_at):
                raise ProfileApprovalRequiredError(
                    "A prod profile must be registered with approved=True, approved_by, and approved_at set"
                )
        if self.approved and not (self.approved_by and self.approved_at):
            raise InvalidDeploymentProfileError("approved=True requires approved_by and approved_at")


class ApprovedProfileRegistry:
    """Append-only, per-(tenant_id, profile_id) version history. There is
    no update or delete method -- a correction is always a new version
    registered at a new effective_from date."""

    def __init__(self) -> None:
        self._versions: dict[tuple[str, str], list[DeploymentProfile]] = {}

    def register_version(self, profile: DeploymentProfile) -> None:
        key = (profile.tenant_id, profile.profile_id)
        versions = self._versions.setdefault(key, [])
        if any(existing.effective_from == profile.effective_from for existing in versions):
            raise DuplicateProfileVersionError(
                f"Profile '{profile.profile_id}' for tenant '{profile.tenant_id}' already has a version "
                f"effective from {profile.effective_from}"
            )
        versions.append(profile)
        versions.sort(key=lambda version: version.effective_from)

    def profile_as_of(self, tenant_id: str, profile_id: str, as_of: str) -> DeploymentProfile:
        applicable = [
            version
            for version in self._versions.get((tenant_id, profile_id), [])
            if version.effective_from <= as_of
        ]
        if not applicable:
            raise UnknownProfileError(
                f"No version of profile '{profile_id}' for tenant '{tenant_id}' is effective as of {as_of}"
            )
        return applicable[-1]

    def versions_for(self, tenant_id: str, profile_id: str) -> tuple[DeploymentProfile, ...]:
        return tuple(self._versions.get((tenant_id, profile_id), ()))
