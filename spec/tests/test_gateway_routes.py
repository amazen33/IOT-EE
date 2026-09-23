import json
from pathlib import Path
import unittest

from gateway.routes import ROUTE_CATALOG, InvalidRouteDefinition, RouteDefinition, build_gateway_route_config

DEPLOYMENT_PATH = Path(__file__).resolve().parents[1] / "deployment" / "m4-gateway-routes.json"


class RouteDefinitionValidationTests(unittest.TestCase):
    def test_path_template_must_be_absolute(self):
        with self.assertRaises(InvalidRouteDefinition):
            RouteDefinition(
                route_id="bad", path_template="tenant/{tenant_id}/x", upstream_service="svc",
                methods=frozenset({"GET"}), auth_required=False,
            )

    def test_path_template_must_carry_tenant_id_segment(self):
        with self.assertRaises(InvalidRouteDefinition):
            RouteDefinition(
                route_id="bad", path_template="/api/x", upstream_service="svc",
                methods=frozenset({"GET"}), auth_required=False,
            )

    def test_unknown_protocol_rejected(self):
        with self.assertRaises(InvalidRouteDefinition):
            RouteDefinition(
                route_id="bad", path_template="/api/tenant/{tenant_id}/x", upstream_service="svc",
                methods=frozenset({"GET"}), auth_required=False, protocol="grpc",
            )

    def test_http_route_requires_a_method(self):
        with self.assertRaises(InvalidRouteDefinition):
            RouteDefinition(
                route_id="bad", path_template="/api/tenant/{tenant_id}/x", upstream_service="svc",
                methods=frozenset(), auth_required=False,
            )

    def test_upstream_service_required(self):
        with self.assertRaises(InvalidRouteDefinition):
            RouteDefinition(
                route_id="bad", path_template="/api/tenant/{tenant_id}/x", upstream_service="",
                methods=frozenset({"GET"}), auth_required=False,
            )

    def test_auth_required_route_must_name_a_permission(self):
        with self.assertRaises(InvalidRouteDefinition):
            RouteDefinition(
                route_id="bad", path_template="/api/tenant/{tenant_id}/x", upstream_service="svc",
                methods=frozenset({"GET"}), auth_required=True,
            )

    def test_required_permission_must_be_in_catalog(self):
        with self.assertRaises(InvalidRouteDefinition):
            RouteDefinition(
                route_id="bad", path_template="/api/tenant/{tenant_id}/x", upstream_service="svc",
                methods=frozenset({"GET"}), auth_required=True, required_permission="not.a.real.permission",
            )

    def test_rate_limit_must_be_positive(self):
        with self.assertRaises(InvalidRouteDefinition):
            RouteDefinition(
                route_id="bad", path_template="/api/tenant/{tenant_id}/x", upstream_service="svc",
                methods=frozenset({"GET"}), auth_required=False, rate_limit_per_minute=0,
            )

    def test_websocket_route_does_not_require_methods(self):
        route = RouteDefinition(
            route_id="ok", path_template="/ws/tenant/{tenant_id}/x", upstream_service="svc",
            methods=frozenset(), auth_required=False, protocol="websocket",
        )
        self.assertEqual(route.protocol, "websocket")


class RouteCatalogTests(unittest.TestCase):
    def test_catalog_route_ids_are_unique(self):
        ids = [route.route_id for route in ROUTE_CATALOG]
        self.assertEqual(len(ids), len(set(ids)))

    def test_report_routes_are_marked_implemented(self):
        by_id = {route.route_id: route for route in ROUTE_CATALOG}
        self.assertTrue(by_id["reports-tank-summary-view"].implemented)
        self.assertTrue(by_id["reports-tank-summary-publish"].implemented)

    def test_ssr_and_websocket_routes_are_marked_not_implemented(self):
        by_id = {route.route_id: route for route in ROUTE_CATALOG}
        self.assertFalse(by_id["ssr-tenant-dashboard"].implemented)
        self.assertFalse(by_id["ws-tenant-telemetry-subscription"].implemented)


class BuildGatewayRouteConfigTests(unittest.TestCase):
    def test_matches_the_committed_deployment_artifact(self):
        # Mirrors M0's validate_plan(deployment/m0-plan.json) discipline:
        # the checked-in config must be exactly reproducible from the
        # catalog, not hand-edited out of sync with it.
        expected = json.loads(DEPLOYMENT_PATH.read_text())
        self.assertEqual(build_gateway_route_config(), expected)

    def test_config_entry_shape(self):
        config = build_gateway_route_config()
        entry = next(e for e in config if e["route_id"] == "reports-tank-summary-view")
        self.assertEqual(entry["uri"], "/api/tenant/{tenant_id}/reports/tank-summary")
        self.assertEqual(entry["methods"], ["GET"])
        self.assertEqual(entry["plugins"]["auth"]["required_permission"], "reports.view")
        self.assertEqual(entry["plugins"]["rate-limit"]["requests_per_minute"], 60)


if __name__ == "__main__":
    unittest.main()
