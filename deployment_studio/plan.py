"""deployment_studio.plan: a deployment plan's draft -> validated ->
approved (or rejected) lifecycle.

Decision (docs/adr/0009-m8-deployment-studio.md): a ``DeploymentPlan``
moves through explicit, RBAC-gated transitions -- never a bare boolean
flag -- and every transition is recorded to a
``deployment_studio.audit.PlanAuditLog`` entry. ``validate_plan`` looks
up the plan's declared profile via
``deployment_studio.profiles.ApprovedProfileRegistry.profile_as_of``
(default-deny: an unknown or not-yet-effective profile fails validation
rather than silently passing) and runs the plan's ``desired_state``
through ``deployment_studio.iac.validate_iac_plan_document``.
``approve_plan``/``reject_plan`` require the ``deployment.approve_plan``
permission (added to ``domain_core.rbac.PERMISSION_CATALOG`` by this
milestone) and only operate on an already-validated plan.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, replace
from enum import Enum

from deployment_studio.audit import PlanAction, PlanAuditEntry, PlanAuditLog
from deployment_studio.iac import validate_iac_plan_document
from deployment_studio.profiles import ApprovedProfileRegistry
from domain_core.rbac import Principal

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_TIMESTAMP_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")
_APPROVAL_PERMISSION = "deployment.approve_plan"


class PlanStatus(str, Enum):
    DRAFT = "draft"
    VALIDATED = "validated"
    APPROVED = "approved"
    REJECTED = "rejected"


class InvalidDeploymentPlanError(ValueError):
    pass


class InvalidPlanTransitionError(ValueError):
    pass


class PlanApprovalPermissionError(PermissionError):
    pass


@dataclass(frozen=True)
class DeploymentPlan:
    plan_id: str
    tenant_id: str
    profile_id: str
    desired_state: dict
    status: PlanStatus = PlanStatus.DRAFT

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.plan_id):
            raise InvalidDeploymentPlanError("Invalid plan_id")
        if not _ID_PATTERN.fullmatch(self.tenant_id):
            raise InvalidDeploymentPlanError("Invalid tenant_id")
        if not _ID_PATTERN.fullmatch(self.profile_id):
            raise InvalidDeploymentPlanError("Invalid profile_id")
        if not isinstance(self.desired_state, dict) or not self.desired_state:
            raise InvalidDeploymentPlanError("desired_state must be a non-empty dict")
        if not isinstance(self.status, PlanStatus):
            raise InvalidDeploymentPlanError("Invalid status")


def create_plan(*, plan_id: str, tenant_id: str, profile_id: str, desired_state: dict,
                 audit_log: PlanAuditLog, actor_principal_id: str, created_at: str) -> DeploymentPlan:
    plan = DeploymentPlan(plan_id=plan_id, tenant_id=tenant_id, profile_id=profile_id, desired_state=desired_state)
    audit_log.record(PlanAuditEntry(
        entry_id=f"{plan_id}-created", plan_id=plan_id, tenant_id=tenant_id, action=PlanAction.CREATED,
        actor_principal_id=actor_principal_id, occurred_at=created_at,
    ))
    return plan


def validate_plan(*, plan: DeploymentPlan, registry: ApprovedProfileRegistry, audit_log: PlanAuditLog,
                   actor_principal_id: str, validated_at: str) -> DeploymentPlan:
    if plan.status is not PlanStatus.DRAFT:
        raise InvalidPlanTransitionError(f"Cannot validate a plan in status '{plan.status.value}'")
    registry.profile_as_of(plan.tenant_id, plan.profile_id, validated_at)  # default-deny; raises if unknown
    validate_iac_plan_document(plan.desired_state)
    validated = replace(plan, status=PlanStatus.VALIDATED)
    audit_log.record(PlanAuditEntry(
        entry_id=f"{plan.plan_id}-validated", plan_id=plan.plan_id, tenant_id=plan.tenant_id,
        action=PlanAction.VALIDATED, actor_principal_id=actor_principal_id, occurred_at=validated_at,
    ))
    return validated


def approve_plan(*, principal: Principal, plan: DeploymentPlan, audit_log: PlanAuditLog,
                  approved_at: str) -> DeploymentPlan:
    if not principal.has_permission(_APPROVAL_PERMISSION):
        raise PlanApprovalPermissionError(f"Principal lacks '{_APPROVAL_PERMISSION}'")
    if plan.status is not PlanStatus.VALIDATED:
        raise InvalidPlanTransitionError(f"Cannot approve a plan in status '{plan.status.value}'")
    approved = replace(plan, status=PlanStatus.APPROVED)
    audit_log.record(PlanAuditEntry(
        entry_id=f"{plan.plan_id}-approved", plan_id=plan.plan_id, tenant_id=plan.tenant_id,
        action=PlanAction.APPROVED, actor_principal_id=principal.principal_id, occurred_at=approved_at,
    ))
    return approved


def reject_plan(*, principal: Principal, plan: DeploymentPlan, reason: str, audit_log: PlanAuditLog,
                 rejected_at: str) -> DeploymentPlan:
    if not principal.has_permission(_APPROVAL_PERMISSION):
        raise PlanApprovalPermissionError(f"Principal lacks '{_APPROVAL_PERMISSION}'")
    if plan.status is not PlanStatus.VALIDATED:
        raise InvalidPlanTransitionError(f"Cannot reject a plan in status '{plan.status.value}'")
    if not reason:
        raise InvalidDeploymentPlanError("A rejection requires a non-empty reason")
    rejected = replace(plan, status=PlanStatus.REJECTED)
    audit_log.record(PlanAuditEntry(
        entry_id=f"{plan.plan_id}-rejected", plan_id=plan.plan_id, tenant_id=plan.tenant_id,
        action=PlanAction.REJECTED, actor_principal_id=principal.principal_id, occurred_at=rejected_at,
        notes=reason,
    ))
    return rejected
