import unittest

from deployment_studio.approval import ProfileApprovalPermissionError, approve_profile
from deployment_studio.profiles import DeploymentProfile, Environment, Provider, Region, Tier
from domain_core.rbac import Principal, PrincipalKind


def _unapproved_profile():
    return DeploymentProfile(
        profile_id="synthetic-profile-a", tenant_id="synthetic-tenant-a", environment=Environment.DEV,
        provider=Provider.ON_PREM, region=Region.ON_PREM_PRIMARY, tier=Tier.STANDARD,
        capabilities=frozenset({"kubernetes"}), effective_from="2026-01-01T00:00:00Z",
    )


def _principal_with(permissions):
    return Principal(
        principal_id="synthetic-operator-001", kind=PrincipalKind.TENANT_OPERATOR,
        tenant_id="synthetic-tenant-a", permissions=frozenset(permissions),
    )


class ApproveProfileTests(unittest.TestCase):
    def test_authorized_principal_can_approve(self):
        approved = approve_profile(
            principal=_principal_with({"deployment.approve_profile"}), profile=_unapproved_profile(),
            approved_at="2026-01-02T00:00:00Z",
        )
        self.assertTrue(approved.approved)
        self.assertEqual(approved.approved_by, "synthetic-operator-001")
        self.assertEqual(approved.approved_at, "2026-01-02T00:00:00Z")

    def test_unauthorized_principal_is_rejected(self):
        with self.assertRaises(ProfileApprovalPermissionError):
            approve_profile(principal=_principal_with(set()), profile=_unapproved_profile(), approved_at="2026-01-02T00:00:00Z")

    def test_system_admin_may_approve(self):
        admin = Principal(principal_id="synthetic-admin-001", kind=PrincipalKind.SYSTEM_ADMIN, tenant_id=None)
        approved = approve_profile(principal=admin, profile=_unapproved_profile(), approved_at="2026-01-02T00:00:00Z")
        self.assertTrue(approved.approved)

    def test_original_profile_object_is_unchanged(self):
        original = _unapproved_profile()
        approve_profile(principal=_principal_with({"deployment.approve_profile"}), profile=original, approved_at="2026-01-02T00:00:00Z")
        self.assertFalse(original.approved)


if __name__ == "__main__":
    unittest.main()
