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


if __name__ == "__main__":
    unittest.main()
