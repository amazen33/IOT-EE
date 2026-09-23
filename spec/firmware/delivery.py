"""firmware-management bounded context: firmware artifact delivery
contract (M5, repository-owner decision: "contract-only, same as M4's
SSR/WebSocket routes").

Reuses ``gateway.routes.RouteDefinition`` / ``build_gateway_route_config``
-- the same validated, APISIX-shaped contract type M4 established --
rather than inventing a second route-contract shape. This module defines
its own catalog and its own deployment artifact
(``deployment/m5-firmware-routes.json``); it does not modify M4's
``gateway.routes.ROUTE_CATALOG`` or ``deployment/m4-gateway-routes.json``,
which stay exactly as M4 shipped and gated them.

No real download, flashing, or device-side installer exists anywhere in
this module -- the route is marked ``implemented=False``, same discipline
as M4's SSR/WebSocket contract-only routes. See docs/firmware.md.
"""

from __future__ import annotations

from gateway.routes import RouteDefinition

FIRMWARE_ROUTE_CATALOG: tuple[RouteDefinition, ...] = (
    RouteDefinition(
        route_id="firmware-artifact-download",
        path_template="/api/tenant/{tenant_id}/firmware/{device_id}/artifact",
        upstream_service="firmware-distribution-service",
        methods=frozenset({"GET"}),
        auth_required=True,
        required_permission="device.command.dispatch.firmware",
        rate_limit_per_minute=30,
        implemented=False,
    ),
)
