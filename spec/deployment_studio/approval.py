"""deployment_studio.approval: RBAC-gated profile approval.

Decision (docs/adr/0009-m8-deployment-studio.md): approving a
``DeploymentProfile`` (turning an unapproved draft into one that can be
registered for ``PROD``, or simply recording an explicit approval for a
non-prod profile) requires the ``deployment.approve_profile`` permission
-- distinct from ``deployment.approve_plan`` (see ``deployment_studio.plan``),
since approving a reusable environment profile and approving one
specific plan against it are different authorities. Mirrors M6's
``evidence.legal_hold`` pattern: a single gated function, no scattered
permission checks.
"""

from __future__ import annotations

from dataclasses import replace

from deployment_studio.profiles import DeploymentProfile
from domain_core.rbac import Principal

_APPROVAL_PERMISSION = "deployment.approve_profile"


class ProfileApprovalPermissionError(PermissionError):
    pass


def approve_profile(*, principal: Principal, profile: DeploymentProfile, approved_at: str) -> DeploymentProfile:
    if not principal.has_permission(_APPROVAL_PERMISSION):
        raise ProfileApprovalPermissionError(f"Principal lacks '{_APPROVAL_PERMISSION}'")
    return replace(profile, approved=True, approved_by=principal.principal_id, approved_at=approved_at)
