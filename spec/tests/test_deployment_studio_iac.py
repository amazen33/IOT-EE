import unittest

from deployment_studio.iac import IacPlanValidationError, validate_iac_plan_document


def _document(**overrides):
    base = {
        "resources": [{"type": "k8s_deployment", "name": "synthetic-app"}],
        "variables": {"replicas": 3},
    }
    base.update(overrides)
    return base


class ValidateIacPlanDocumentTests(unittest.TestCase):
    def test_valid_document_passes(self):
        validate_iac_plan_document(_document())  # must not raise

    def test_rejects_non_dict(self):
        with self.assertRaises(IacPlanValidationError):
            validate_iac_plan_document(["not", "a", "dict"])

    def test_rejects_apply_true(self):
        with self.assertRaises(IacPlanValidationError):
            validate_iac_plan_document(_document(apply=True))

    def test_apply_false_is_fine(self):
        validate_iac_plan_document(_document(apply=False))  # must not raise

    def test_rejects_missing_resources_key(self):
        document = _document()
        del document["resources"]
        with self.assertRaises(IacPlanValidationError):
            validate_iac_plan_document(document)

    def test_rejects_empty_resources_list(self):
        with self.assertRaises(IacPlanValidationError):
            validate_iac_plan_document(_document(resources=[]))

    def test_rejects_resource_missing_type_or_name(self):
        with self.assertRaises(IacPlanValidationError):
            validate_iac_plan_document(_document(resources=[{"name": "synthetic-app"}]))
        with self.assertRaises(IacPlanValidationError):
            validate_iac_plan_document(_document(resources=[{"type": "k8s_deployment"}]))

    def test_rejects_non_dict_variables(self):
        with self.assertRaises(IacPlanValidationError):
            validate_iac_plan_document(_document(variables=["not", "a", "dict"]))


if __name__ == "__main__":
    unittest.main()
