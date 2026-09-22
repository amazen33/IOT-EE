import unittest

from deployment_studio.gitops import DriftStatus, reconcile_desired_state


class ReconcileDesiredStateTests(unittest.TestCase):
    def test_identical_states_are_in_sync(self):
        result = reconcile_desired_state(
            plan_id="synthetic-plan-a", desired_state={"replicas": 3}, observed_state={"replicas": 3},
        )
        self.assertTrue(result.is_in_sync)
        self.assertEqual(result.differences, ())

    def test_differing_value_is_reported_as_drift(self):
        result = reconcile_desired_state(
            plan_id="synthetic-plan-a", desired_state={"replicas": 3}, observed_state={"replicas": 2},
        )
        self.assertEqual(result.status, DriftStatus.DRIFTED)
        self.assertEqual(len(result.differences), 1)
        self.assertEqual(result.differences[0].field_name, "replicas")

    def test_field_present_only_in_desired_is_drift(self):
        result = reconcile_desired_state(
            plan_id="synthetic-plan-a", desired_state={"replicas": 3, "image": "app:v2"}, observed_state={"replicas": 3},
        )
        self.assertFalse(result.is_in_sync)
        self.assertEqual([d.field_name for d in result.differences], ["image"])

    def test_field_present_only_in_observed_is_drift(self):
        result = reconcile_desired_state(
            plan_id="synthetic-plan-a", desired_state={"replicas": 3}, observed_state={"replicas": 3, "extra_label": "x"},
        )
        self.assertFalse(result.is_in_sync)
        self.assertEqual([d.field_name for d in result.differences], ["extra_label"])

    def test_empty_states_are_in_sync(self):
        result = reconcile_desired_state(plan_id="synthetic-plan-a", desired_state={}, observed_state={})
        self.assertTrue(result.is_in_sync)


if __name__ == "__main__":
    unittest.main()
