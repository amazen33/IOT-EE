import unittest

from deployment_studio.audit import PlanAction, PlanAuditLog
from deployment_studio.plan import (
    InvalidDeploymentPlanError,
    InvalidPlanTransitionError,
    PlanApprovalPermissionError,
    PlanStatus,
    approve_plan,
    create_plan,
    reject_plan,
    validate_plan,
)
from deployment_studio.profiles import ApprovedProfileRegistry, DeploymentProfile, Environment, Provider, Region, Tier
from domain_core.rbac import Principal, PrincipalKind


def _registry():
    registry = ApprovedProfileRegistry()
    registry.register_version(DeploymentProfile(
        profile_id="synthetic-profile-a", tenant_id="synthetic-tenant-a", environment=Environment.DEV,
        provider=Provider.ON_PREM, region=Region.ON_PREM_PRIMARY, tier=Tier.STANDARD,
        capabilities=frozenset({"kubernetes"}), effective_from="2026-01-01T00:00:00Z",
    ))
    return registry


def _desired_state():
    return {"resources": [{"type": "k8s_deployment", "name": "synthetic-app"}], "variables": {"replicas": 3}}


def _approver():
    return Principal(
        principal_id="synthetic-operator-001", kind=PrincipalKind.TENANT_OPERATOR,
        tenant_id="synthetic-tenant-a", permissions=frozenset({"deployment.approve_plan"}),
    )


class CreatePlanTests(unittest.TestCase):
    def test_creates_a_draft_plan_and_logs_it(self):
        log = PlanAuditLog()
        plan = create_plan(
            plan_id="synthetic-plan-a", tenant_id="synthetic-tenant-a", profile_id="synthetic-profile-a",
            desired_state=_desired_state(), audit_log=log, actor_principal_id="synthetic-operator-001",
            created_at="2026-01-01T00:00:00Z",
        )
        self.assertEqual(plan.status, PlanStatus.DRAFT)
        self.assertEqual([e.action for e in log.entries_for_plan("synthetic-plan-a")], [PlanAction.CREATED])

    def test_rejects_empty_desired_state(self):
        with self.assertRaises(InvalidDeploymentPlanError):
            create_plan(
                plan_id="synthetic-plan-a", tenant_id="synthetic-tenant-a", profile_id="synthetic-profile-a",
                desired_state={}, audit_log=PlanAuditLog(), actor_principal_id="synthetic-operator-001",
                created_at="2026-01-01T00:00:00Z",
            )


class ValidatePlanTests(unittest.TestCase):
    def setUp(self):
        self.log = PlanAuditLog()
        self.registry = _registry()
        self.plan = create_plan(
            plan_id="synthetic-plan-a", tenant_id="synthetic-tenant-a", profile_id="synthetic-profile-a",
            desired_state=_desired_state(), audit_log=self.log, actor_principal_id="synthetic-operator-001",
            created_at="2026-01-01T00:00:00Z",
        )

    def test_validates_a_draft_plan_against_an_effective_profile(self):
        validated = validate_plan(
            plan=self.plan, registry=self.registry, audit_log=self.log,
            actor_principal_id="synthetic-operator-001", validated_at="2026-01-02T00:00:00Z",
        )
        self.assertEqual(validated.status, PlanStatus.VALIDATED)

    def test_fails_default_deny_for_an_unregistered_profile(self):
        orphan = create_plan(
            plan_id="synthetic-plan-orphan", tenant_id="synthetic-tenant-a", profile_id="synthetic-profile-unregistered",
            desired_state=_desired_state(), audit_log=self.log, actor_principal_id="synthetic-operator-001",
            created_at="2026-01-01T00:00:00Z",
        )
        with self.assertRaises(KeyError):
            validate_plan(
                plan=orphan, registry=self.registry, audit_log=self.log,
                actor_principal_id="synthetic-operator-001", validated_at="2026-01-02T00:00:00Z",
            )

    def test_fails_iac_validation_for_a_malformed_desired_state(self):
        bad_plan = create_plan(
            plan_id="synthetic-plan-bad", tenant_id="synthetic-tenant-a", profile_id="synthetic-profile-a",
            desired_state={"apply": True, "resources": [], "variables": {}}, audit_log=self.log,
            actor_principal_id="synthetic-operator-001", created_at="2026-01-01T00:00:00Z",
        )
        with self.assertRaises(ValueError):
            validate_plan(
                plan=bad_plan, registry=self.registry, audit_log=self.log,
                actor_principal_id="synthetic-operator-001", validated_at="2026-01-02T00:00:00Z",
            )

    def test_cannot_validate_a_non_draft_plan(self):
        validated = validate_plan(
            plan=self.plan, registry=self.registry, audit_log=self.log,
            actor_principal_id="synthetic-operator-001", validated_at="2026-01-02T00:00:00Z",
        )
        with self.assertRaises(InvalidPlanTransitionError):
            validate_plan(
                plan=validated, registry=self.registry, audit_log=self.log,
                actor_principal_id="synthetic-operator-001", validated_at="2026-01-03T00:00:00Z",
            )


class ApproveAndRejectPlanTests(unittest.TestCase):
    def setUp(self):
        self.log = PlanAuditLog()
        self.registry = _registry()
        plan = create_plan(
            plan_id="synthetic-plan-a", tenant_id="synthetic-tenant-a", profile_id="synthetic-profile-a",
            desired_state=_desired_state(), audit_log=self.log, actor_principal_id="synthetic-operator-001",
            created_at="2026-01-01T00:00:00Z",
        )
        self.validated_plan = validate_plan(
            plan=plan, registry=self.registry, audit_log=self.log,
            actor_principal_id="synthetic-operator-001", validated_at="2026-01-02T00:00:00Z",
        )

    def test_approve_requires_permission(self):
        unauthorized = Principal(
            principal_id="synthetic-operator-002", kind=PrincipalKind.TENANT_OPERATOR,
            tenant_id="synthetic-tenant-a", permissions=frozenset(),
        )
        with self.assertRaises(PlanApprovalPermissionError):
            approve_plan(principal=unauthorized, plan=self.validated_plan, audit_log=self.log, approved_at="2026-01-03T00:00:00Z")

    def test_approve_succeeds_and_logs(self):
        approved = approve_plan(principal=_approver(), plan=self.validated_plan, audit_log=self.log, approved_at="2026-01-03T00:00:00Z")
        self.assertEqual(approved.status, PlanStatus.APPROVED)
        actions = [e.action for e in self.log.entries_for_plan("synthetic-plan-a")]
        self.assertEqual(actions, [PlanAction.CREATED, PlanAction.VALIDATED, PlanAction.APPROVED])

    def test_cannot_approve_a_draft_plan(self):
        draft = create_plan(
            plan_id="synthetic-plan-draft", tenant_id="synthetic-tenant-a", profile_id="synthetic-profile-a",
            desired_state=_desired_state(), audit_log=self.log, actor_principal_id="synthetic-operator-001",
            created_at="2026-01-01T00:00:00Z",
        )
        with self.assertRaises(InvalidPlanTransitionError):
            approve_plan(principal=_approver(), plan=draft, audit_log=self.log, approved_at="2026-01-03T00:00:00Z")

    def test_reject_requires_a_reason(self):
        with self.assertRaises(InvalidDeploymentPlanError):
            reject_plan(principal=_approver(), plan=self.validated_plan, reason="", audit_log=self.log, rejected_at="2026-01-03T00:00:00Z")

    def test_reject_succeeds_and_logs_the_reason(self):
        rejected = reject_plan(
            principal=_approver(), plan=self.validated_plan, reason="capacity concerns", audit_log=self.log,
            rejected_at="2026-01-03T00:00:00Z",
        )
        self.assertEqual(rejected.status, PlanStatus.REJECTED)
        entries = self.log.entries_for_plan("synthetic-plan-a")
        self.assertEqual(entries[-1].notes, "capacity concerns")


if __name__ == "__main__":
    unittest.main()
