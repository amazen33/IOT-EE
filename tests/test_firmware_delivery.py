import json
from pathlib import Path
import unittest

from firmware.delivery import FIRMWARE_ROUTE_CATALOG
from gateway.routes import build_gateway_route_config

DEPLOYMENT_PATH = Path(__file__).resolve().parents[1] / "deployment" / "m5-firmware-routes.json"


class FirmwareRouteCatalogTests(unittest.TestCase):
    def test_catalog_has_one_route(self):
        self.assertEqual(len(FIRMWARE_ROUTE_CATALOG), 1)

    def test_route_is_not_implemented(self):
        route = FIRMWARE_ROUTE_CATALOG[0]
        self.assertFalse(route.implemented)

    def test_route_requires_firmware_dispatch_permission(self):
        route = FIRMWARE_ROUTE_CATALOG[0]
        self.assertEqual(route.required_permission, "device.command.dispatch.firmware")

    def test_route_path_is_tenant_and_device_scoped(self):
        route = FIRMWARE_ROUTE_CATALOG[0]
        self.assertIn("{tenant_id}", route.path_template)
        self.assertIn("{device_id}", route.path_template)


class FirmwareDeploymentArtifactTests(unittest.TestCase):
    def test_matches_the_committed_deployment_artifact(self):
        expected = json.loads(DEPLOYMENT_PATH.read_text())
        self.assertEqual(build_gateway_route_config(FIRMWARE_ROUTE_CATALOG), expected)

    def test_m4_route_catalog_and_artifact_are_untouched(self):
        # This module must not have modified gateway.routes.ROUTE_CATALOG
        # or M4's own deployment artifact.
        from gateway.routes import ROUTE_CATALOG

        m4_deployment_path = Path(__file__).resolve().parents[1] / "deployment" / "m4-gateway-routes.json"
        m4_expected = json.loads(m4_deployment_path.read_text())
        self.assertEqual(build_gateway_route_config(ROUTE_CATALOG), m4_expected)
        self.assertNotIn("firmware-artifact-download", [route.route_id for route in ROUTE_CATALOG])


if __name__ == "__main__":
    unittest.main()
