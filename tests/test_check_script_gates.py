"""Negative tests for scripts/check.py's mechanical import-policy gates.

Per code review: a gate that is never exercised for failure is trusted on
faith. These tests plant a temporary file containing a disallowed import
inside a *subdirectory* of a scanned package (proving the rglob fix
actually covers subpackages, not just the package root), assert the
relevant check function raises, and clean the planted file up
afterwards. They also assert each check still passes cleanly on the
real, current codebase.
"""

import unittest

import scripts.check as check


class _PlantedImportFixture:
    """Creates <package>/_gate_test_subdir/_planted.py containing
    ``import_line``, and removes both the file and the directory on
    exit, even if the test body raises."""

    def __init__(self, package_name: str, import_line: str) -> None:
        self._package_dir = check.ROOT / package_name / "_gate_test_subdir"
        self._planted_file = self._package_dir / "_planted_import.py"
        self._import_line = import_line

    def __enter__(self) -> None:
        self._package_dir.mkdir(exist_ok=False)
        self._planted_file.write_text(self._import_line + "\n")

    def __exit__(self, *exc_info) -> None:
        self._planted_file.unlink(missing_ok=True)
        self._package_dir.rmdir()


class BillingIsolationGateTests(unittest.TestCase):
    def test_passes_on_the_current_codebase(self):
        check._check_billing_isolation()  # must not raise

    def test_raises_when_a_core_subpackage_imports_billing(self):
        with _PlantedImportFixture("domain_core", "import billing"):
            with self.assertRaises(ValueError):
                check._check_billing_isolation()
        # Cleanup happened; the gate is clean again.
        check._check_billing_isolation()

    def test_raises_for_a_from_import_form_too(self):
        with _PlantedImportFixture("evidence", "from billing import flags"):
            with self.assertRaises(ValueError):
                check._check_billing_isolation()


class DeploymentStudioIsolationGateTests(unittest.TestCase):
    def test_passes_on_the_current_codebase(self):
        check._check_deployment_studio_isolation()  # must not raise

    def test_raises_when_deployment_studio_imports_evidence(self):
        with _PlantedImportFixture("deployment_studio", "import evidence"):
            with self.assertRaises(ValueError):
                check._check_deployment_studio_isolation()
        # Cleanup happened; the gate is clean again.
        check._check_deployment_studio_isolation()

    def test_raises_for_a_from_import_form_too(self):
        with _PlantedImportFixture("deployment_studio", "from firmware import rollout"):
            with self.assertRaises(ValueError):
                check._check_deployment_studio_isolation()

    def test_billing_isolation_also_covers_deployment_studio(self):
        with _PlantedImportFixture("deployment_studio", "import billing"):
            with self.assertRaises(ValueError):
                check._check_billing_isolation()


class NoForbiddenImportsGateTests(unittest.TestCase):
    def test_passes_on_the_current_codebase_for_every_checked_package(self):
        for package_name in check._CORE_PACKAGES_FORBIDDEN_FROM_IMPORTING_BILLING:
            check._check_no_forbidden_imports(package_name)  # must not raise

    def test_raises_when_a_subpackage_imports_a_forbidden_module(self):
        with _PlantedImportFixture("domain_core", "import socket"):
            with self.assertRaises(ValueError):
                check._check_no_forbidden_imports("domain_core")
        # Cleanup happened; the gate is clean again.
        check._check_no_forbidden_imports("domain_core")

    def test_raises_for_a_from_import_form_too(self):
        with _PlantedImportFixture("firmware", "from boto3 import client"):
            with self.assertRaises(ValueError):
                check._check_no_forbidden_imports("firmware")


class AdaptersIsolationGateTests(unittest.TestCase):
    def test_passes_on_the_current_codebase(self):
        check._check_adapters_isolation()

    def test_raises_when_a_core_package_imports_adapters(self):
        with _PlantedImportFixture("evidence", "import adapters"):
            with self.assertRaises(ValueError):
                check._check_adapters_isolation()
        check._check_adapters_isolation()

    def test_raises_when_worm_s3_imports_billing(self):
        with _PlantedImportFixture("adapters/worm_s3", "import billing"):
            with self.assertRaises(ValueError):
                check._check_adapters_isolation()

    def test_raises_for_a_from_import_form_too(self):
        with _PlantedImportFixture("adapters/worm_s3", "from deployment_studio import plan"):
            with self.assertRaises(ValueError):
                check._check_adapters_isolation()


class Boto3ConfinedToAdaptersGateTests(unittest.TestCase):
    def test_passes_on_the_current_codebase(self):
        check._check_boto3_confined_to_adapters()

    def test_raises_when_a_core_package_imports_boto3(self):
        with _PlantedImportFixture("domain_core", "import boto3"):
            with self.assertRaises(ValueError):
                check._check_boto3_confined_to_adapters()

    def test_raises_when_a_test_module_imports_botocore(self):
        with _PlantedImportFixture("tests", "import botocore"):
            with self.assertRaises(ValueError):
                check._check_boto3_confined_to_adapters()


class Boto3LazyImportGateTests(unittest.TestCase):
    def test_passes_on_the_current_codebase(self):
        check._check_boto3_imported_lazily()

    def test_raises_on_a_module_scope_boto3_import(self):
        target = check.ROOT / "adapters" / "worm_s3" / "_gate_test_eager_import.py"
        target.write_text("import boto3\n")
        try:
            with self.assertRaises(ValueError):
                check._check_boto3_imported_lazily()
        finally:
            target.unlink()

    def test_does_not_raise_when_boto3_import_is_inside_a_function(self):
        target = check.ROOT / "adapters" / "worm_s3" / "_gate_test_lazy_import.py"
        target.write_text("def f():\n    import boto3\n    return boto3\n")
        try:
            check._check_boto3_imported_lazily()  # must not raise
        finally:
            target.unlink()


class AdaptersImportAllowlistGateTests(unittest.TestCase):
    def test_passes_on_the_current_codebase(self):
        check._check_import_allowlist("adapters/worm_s3", check._ADAPTERS_WORM_S3_IMPORT_ALLOWLIST)

    def test_raises_on_an_unlisted_import(self):
        with _PlantedImportFixture("adapters/worm_s3", "import socket"):
            with self.assertRaises(ValueError):
                check._check_import_allowlist("adapters/worm_s3", check._ADAPTERS_WORM_S3_IMPORT_ALLOWLIST)


class RequirementsTxtNoBoto3GateTests(unittest.TestCase):
    def test_passes_on_the_current_codebase(self):
        check._check_requirements_txt_has_no_boto3()

    def test_raises_if_boto3_added_to_requirements_txt(self):
        req_path = check.ROOT / "requirements.txt"
        original = req_path.read_text()
        req_path.write_text(original + "\nboto3>=1.34.0\n")
        try:
            with self.assertRaises(ValueError):
                check._check_requirements_txt_has_no_boto3()
        finally:
            req_path.write_text(original)


if __name__ == "__main__":
    unittest.main()
