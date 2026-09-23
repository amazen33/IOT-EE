import unittest

from deployment_studio.profiles import (
    ApprovedProfileRegistry,
    DeploymentProfile,
    DuplicateProfileVersionError,
    Environment,
    InvalidDeploymentProfileError,
    ProfileApprovalRequiredError,
    Provider,
    Region,
    Tier,
    UnknownProfileError,
)


def _profile(**overrides):
    base = dict(
        profile_id="synthetic-profile-a", tenant_id="synthetic-tenant-a", environment=Environment.DEV,
        provider=Provider.ON_PREM, region=Region.ON_PREM_PRIMARY, tier=Tier.STANDARD,
        capabilities=frozenset({"kubernetes"}), effective_from="2026-01-01T00:00:00Z",
    )
    base.update(overrides)
    return DeploymentProfile(**base)


class DeploymentProfileValidationTests(unittest.TestCase):
    def test_valid_dev_profile_constructs(self):
        self.assertEqual(_profile().environment, Environment.DEV)

    def test_rejects_mismatched_provider_and_region(self):
        with self.assertRaises(InvalidDeploymentProfileError):
            _profile(provider=Provider.ON_PREM, region=Region.AWS_US_EAST_1)

    def test_rejects_empty_capabilities(self):
        with self.assertRaises(InvalidDeploymentProfileError):
            _profile(capabilities=frozenset())

    def test_rejects_negative_rpo(self):
        with self.assertRaises(InvalidDeploymentProfileError):
            _profile(rpo_seconds=-1)

    def test_prod_without_approval_is_rejected(self):
        with self.assertRaises(ProfileApprovalRequiredError):
            _profile(environment=Environment.PROD, provider=Provider.AWS, region=Region.AWS_US_EAST_1)

    def test_prod_with_full_approval_constructs(self):
        profile = _profile(
            environment=Environment.PROD, provider=Provider.AWS, region=Region.AWS_US_EAST_1,
            approved=True, approved_by="synthetic-admin-001", approved_at="2026-01-02T00:00:00Z",
        )
        self.assertTrue(profile.approved)

    def test_approved_true_requires_approver_and_timestamp(self):
        with self.assertRaises(InvalidDeploymentProfileError):
            _profile(approved=True, approved_by=None, approved_at=None)


class ApprovedProfileRegistryTests(unittest.TestCase):
    def setUp(self):
        self.registry = ApprovedProfileRegistry()
        self.registry.register_version(_profile(effective_from="2026-01-01T00:00:00Z"))
        self.registry.register_version(_profile(effective_from="2026-06-01T00:00:00Z", tier=Tier.HIGH_AVAILABILITY))

    def test_default_deny_before_any_version_is_effective(self):
        with self.assertRaises(UnknownProfileError):
            self.registry.profile_as_of("synthetic-tenant-a", "synthetic-profile-a", "2025-12-31T00:00:00Z")

    def test_default_deny_for_unregistered_profile_id(self):
        with self.assertRaises(UnknownProfileError):
            self.registry.profile_as_of("synthetic-tenant-a", "synthetic-profile-unregistered", "2026-03-01T00:00:00Z")

    def test_default_deny_for_unregistered_tenant(self):
        with self.assertRaises(UnknownProfileError):
            self.registry.profile_as_of("synthetic-tenant-b", "synthetic-profile-a", "2026-03-01T00:00:00Z")

    def test_resolves_the_version_in_effect(self):
        self.assertEqual(
            self.registry.profile_as_of("synthetic-tenant-a", "synthetic-profile-a", "2026-03-01T00:00:00Z").tier,
            Tier.STANDARD,
        )
        self.assertEqual(
            self.registry.profile_as_of("synthetic-tenant-a", "synthetic-profile-a", "2026-06-01T00:00:00Z").tier,
            Tier.HIGH_AVAILABILITY,
        )

    def test_duplicate_effective_from_is_rejected(self):
        with self.assertRaises(DuplicateProfileVersionError):
            self.registry.register_version(_profile(effective_from="2026-01-01T00:00:00Z", tier=Tier.HIGH_AVAILABILITY))

    def test_versions_for_returns_registered_versions_and_is_tenant_scoped(self):
        self.assertEqual(len(self.registry.versions_for("synthetic-tenant-a", "synthetic-profile-a")), 2)
        self.assertEqual(self.registry.versions_for("synthetic-tenant-b", "synthetic-profile-a"), ())


if __name__ == "__main__":
    unittest.main()
