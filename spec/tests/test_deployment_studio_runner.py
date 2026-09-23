import unittest

from deployment_studio.runner import InvalidRunnerPolicyError, RunnerPolicy


def _policy(**overrides):
    base = dict(
        policy_id="synthetic-runner-policy-a", profile_id="synthetic-profile-a",
        allowed_actions=frozenset({"plan", "validate"}), permission_scopes=frozenset({"read:terraform-state"}),
    )
    base.update(overrides)
    return RunnerPolicy(**base)


class RunnerPolicyTests(unittest.TestCase):
    def test_valid_policy_constructs(self):
        self.assertIn("plan", _policy().allowed_actions)

    def test_rejects_apply_action(self):
        with self.assertRaises(InvalidRunnerPolicyError):
            _policy(allowed_actions=frozenset({"plan", "apply"}))

    def test_rejects_destroy_action(self):
        with self.assertRaises(InvalidRunnerPolicyError):
            _policy(allowed_actions=frozenset({"destroy"}))

    def test_rejects_empty_allowed_actions(self):
        with self.assertRaises(InvalidRunnerPolicyError):
            _policy(allowed_actions=frozenset())

    def test_rejects_empty_permission_scopes(self):
        with self.assertRaises(InvalidRunnerPolicyError):
            _policy(permission_scopes=frozenset())


if __name__ == "__main__":
    unittest.main()
