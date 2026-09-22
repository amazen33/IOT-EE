import json
from pathlib import Path
import unittest

from deployment_studio.profiles import ApprovedProfileRegistry, DeploymentProfile, Environment, Provider, Region, Tier

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "deployment_profiles.synthetic.json"


def _profile_from_entry(entry):
    return DeploymentProfile(
        profile_id=entry["profile_id"],
        tenant_id=entry["tenant_id"],
        environment=Environment(entry["environment"]),
        provider=Provider(entry["provider"]),
        region=Region(entry["region"]),
        tier=Tier(entry["tier"]),
        capabilities=frozenset(entry["capabilities"]),
        effective_from=entry["effective_from"],
        rpo_seconds=entry["rpo_seconds"],
        rto_seconds=entry["rto_seconds"],
        approved=entry["approved"],
        approved_by=entry["approved_by"],
        approved_at=entry["approved_at"],
    )


class DeploymentProfileFixtureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(FIXTURE_PATH.read_text())

    def test_fixture_ids_are_synthetic(self):
        for entry in self.fixture["profiles"]:
            self.assertTrue(entry["profile_id"].startswith("synthetic-"))
            self.assertTrue(entry["tenant_id"].startswith("synthetic-"))

    def test_all_fixture_profiles_construct_and_register(self):
        registry = ApprovedProfileRegistry()
        for entry in self.fixture["profiles"]:
            registry.register_version(_profile_from_entry(entry))
        resolved = registry.profile_as_of("synthetic-tenant-a", "synthetic-profile-cloud-prod", "2026-06-01T00:00:00Z")
        self.assertEqual(resolved.environment, Environment.PROD)
        self.assertTrue(resolved.approved)


if __name__ == "__main__":
    unittest.main()
