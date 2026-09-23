"""gateway bounded context: route/policy contract, modeled for eventual
translation into a real APISIX Admin API configuration.

Decision (docs/adr/0005-m4-gateway-reporting.md): this module produces an
inert, APISIX-shaped route/policy description -- not a literal APISIX
Admin API payload, and it never calls APISIX, applies a route, or opens a
socket. Same "inert plan" discipline as M0's deployment/m0-plan.json: a
data artifact that can be reviewed and diffed before any real gateway
exists, matching CLAUDE.md's "no unrestricted cloud commands... in
browser flows" and "M0 must not provision infrastructure" spirit extended
to this milestone (M4 introduces no infrastructure either).

Every tenant-scoped route's path template must carry a literal
'{tenant_id}' segment -- a mechanical, static check that a route
authorizing per-tenant data cannot be defined without a tenant scope in
its own path, mirroring how domain_core.commands makes cross-tenant
dispatch a structural impossibility rather than a policy note.
"""

from __future__ import annotations

from dataclasses import dataclass

from domain_core.rbac import PERMISSION_CATALOG

_VALID_PROTOCOLS = frozenset({"http", "websocket"})


class InvalidRouteDefinition(ValueError):
    pass


@dataclass(frozen=True)
class RouteDefinition:
    route_id: str
    path_template: str
    upstream_service: str
    methods: frozenset[str]
    auth_required: bool
    required_permission: str | None = None
    rate_limit_per_minute: int | None = None
    protocol: str = "http"
    implemented: bool = True
    """False marks a route whose *contract* is defined this milestone but
    whose backend (SSR page, WebSocket server) is not yet implemented --
    see docs/gateway.md. The route is still validated and included in the
    generated config, so the contract is reviewable now."""

    def __post_init__(self) -> None:
        if not self.route_id:
            raise ValueError("route_id is required")
        if not self.path_template.startswith("/"):
            raise InvalidRouteDefinition(f"'{self.route_id}': path_template must be absolute")
        if self.protocol not in _VALID_PROTOCOLS:
            raise InvalidRouteDefinition(f"'{self.route_id}': unknown protocol '{self.protocol}'")
        if "{tenant_id}" not in self.path_template:
            raise InvalidRouteDefinition(
                f"'{self.route_id}': tenant-scoped routes must carry a literal '{{tenant_id}}' path segment"
            )
        if not self.upstream_service:
            raise InvalidRouteDefinition(f"'{self.route_id}': upstream_service is required")
        if self.protocol == "http" and not self.methods:
            raise InvalidRouteDefinition(f"'{self.route_id}': http routes require at least one method")
        if self.auth_required and self.required_permission is None:
            raise InvalidRouteDefinition(f"'{self.route_id}': auth_required routes must name a required_permission")
        if self.required_permission is not None and self.required_permission not in PERMISSION_CATALOG:
            raise InvalidRouteDefinition(
                f"'{self.route_id}': required_permission '{self.required_permission}' is not in PERMISSION_CATALOG"
            )
        if self.rate_limit_per_minute is not None and self.rate_limit_per_minute <= 0:
            raise InvalidRouteDefinition(f"'{self.route_id}': rate_limit_per_minute must be positive")


# The M4 route catalog. Report routes are implemented end-to-end this
# milestone (auth -> permission -> reporting read-model); the SSR page and
# WebSocket subscription routes are contract-only (implemented=False) --
# their backends are explicitly out of scope this slice, see docs/gateway.md.
ROUTE_CATALOG: tuple[RouteDefinition, ...] = (
    RouteDefinition(
        route_id="reports-tank-summary-view",
        path_template="/api/tenant/{tenant_id}/reports/tank-summary",
        upstream_service="reporting-service",
        methods=frozenset({"GET"}),
        auth_required=True,
        required_permission="reports.view",
        rate_limit_per_minute=60,
    ),
    RouteDefinition(
        route_id="reports-tank-summary-publish",
        path_template="/api/tenant/{tenant_id}/reports/tank-summary/publish",
        upstream_service="reporting-service",
        methods=frozenset({"POST"}),
        auth_required=True,
        required_permission="reports.publish",
        rate_limit_per_minute=10,
    ),
    RouteDefinition(
        route_id="ssr-tenant-dashboard",
        path_template="/tenant/{tenant_id}/dashboard",
        upstream_service="ssr-frontend",
        methods=frozenset({"GET"}),
        auth_required=True,
        required_permission="reports.view",
        rate_limit_per_minute=120,
        implemented=False,
    ),
    RouteDefinition(
        route_id="ws-tenant-telemetry-subscription",
        path_template="/ws/tenant/{tenant_id}/telemetry",
        upstream_service="telemetry-ws-service",
        methods=frozenset({"GET"}),
        auth_required=True,
        required_permission="device.view",
        protocol="websocket",
        implemented=False,
    ),
)


def build_gateway_route_config(routes: tuple[RouteDefinition, ...] = ROUTE_CATALOG) -> list[dict]:
    """Renders the route catalog as an inert, JSON-serializable
    configuration document. This is a contract for what an APISIX route
    set (or an equivalent gateway's) would need to express -- uri,
    methods, upstream, a rate-limit plugin slot, and an auth/permission
    requirement -- not a literal Admin API request body, and applying it
    to a real gateway is not performed anywhere in this module.
    """
    config = []
    for route in routes:
        plugins: dict = {}
        if route.rate_limit_per_minute is not None:
            plugins["rate-limit"] = {"requests_per_minute": route.rate_limit_per_minute}
        if route.auth_required:
            plugins["auth"] = {"required_permission": route.required_permission}
        config.append(
            {
                "route_id": route.route_id,
                "uri": route.path_template,
                "methods": sorted(route.methods),
                "upstream_service": route.upstream_service,
                "protocol": route.protocol,
                "plugins": plugins,
                "implemented": route.implemented,
            }
        )
    return config
