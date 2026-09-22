"""End-to-end smoke test wiring tenancy + assets + rbac + devices + commands
together from the synthetic M2 fixture, mirroring how scripts/check.py
validates fixtures/telemetry.synthetic.json and
fixtures/migration_sources.synthetic.json for earlier stages."""

import json
import unittest
from pathlib import Path

from domain_core.assets import AssetHierarchy, CommunicationType, Region, Site, Tank, TankMaterial
from domain_core.commands import CommandStatus, CommandStore, OperatorDeviceScope
from domain_core.devices import Device, DeviceId, DeviceRegistry
from domain_core.rbac import Principal, PrincipalKind, Role, assign_role, create_role
from domain_core.tenancy import IsolationMode, Tenant, TenantSizeTier
from domain_core.units import UtcTimestamp

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PATH = ROOT / "fixtures" / "domain_core.synthetic.json"


class FixtureWiringTests(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(FIXTURE_PATH.read_text())

    def test_all_ids_are_synthetic(self):
        for tenant in self.fixture["tenants"]:
            self.assertTrue(tenant["tenant_id"].startswith("synthetic-"))

    def test_tenants_load_and_isolation_mode_matches_tier(self):
        tenants = {}
        for entry in self.fixture["tenants"]:
            tenant = Tenant(
                tenant_id=entry["tenant_id"],
                name=entry["name"],
                tier=TenantSizeTier(entry["tier"]),
                provisioned_device_count=entry["provisioned_device_count"],
            )
            tenants[tenant.tenant_id] = tenant
        standard = tenants["synthetic-tenant-standard"]
        enterprise = tenants["synthetic-tenant-enterprise"]
        self.assertEqual(standard.isolation_mode, IsolationMode.SHARED_RLS)
        self.assertEqual(enterprise.isolation_mode, IsolationMode.SCHEMA_PER_TENANT)
        self.assertIsNotNone(enterprise.schema_name)

    def test_hierarchy_and_device_and_command_end_to_end(self):
        hierarchy = AssetHierarchy()
        for region in self.fixture["regions"]:
            hierarchy.add_region(Region(**region))
        for site in self.fixture["sites"]:
            hierarchy.add_site(Site(**site))
        for tank in self.fixture["tanks"]:
            hierarchy.add_tank(
                Tank(
                    tank_id=tank["tank_id"],
                    tenant_id=tank["tenant_id"],
                    site_id=tank["site_id"],
                    material=TankMaterial(tank["material"]),
                    communication_type=CommunicationType(tank["communication_type"]),
                )
            )
        tank = hierarchy.get_tank("synthetic-tank-001")

        admin = Principal(
            principal_id="synthetic-admin-1", kind=PrincipalKind.TENANT_ADMIN,
            tenant_id="synthetic-tenant-standard",
            permissions=frozenset({"tenant.manage_roles", "device.view", "device.command.dispatch"}),
        )
        role_fixture = self.fixture["roles"][0]
        role = create_role(
            assigner=admin, role_id=role_fixture["role_id"], tenant_id=role_fixture["tenant_id"],
            name=role_fixture["name"], permissions=frozenset(role_fixture["permissions"]),
        )
        operator = Principal(
            principal_id="synthetic-operator-1", kind=PrincipalKind.TENANT_OPERATOR,
            tenant_id="synthetic-tenant-standard", permissions=role.permissions,
        )
        assign_role(assigner=admin, assignee_principal_id=operator.principal_id, role=role)

        registry = DeviceRegistry()
        device = registry.register(
            Device(device_id=DeviceId.generate(tank.tenant_id), tank_id=tank.tank_id, label="Synthetic sensor")
        )

        now = UtcTimestamp.now()
        scope = OperatorDeviceScope(
            principal_id=operator.principal_id, tenant_id=operator.tenant_id,
            device_ids=frozenset({device.device_id.value}), writable_properties=frozenset({"target_level_percent"}),
        )
        store = CommandStore()
        command = store.dispatch(
            principal=operator, scope=scope, device=device, property_name="target_level_percent",
            desired_value=55, ttl_seconds=300, now=now,
        )
        self.assertEqual(command.status, CommandStatus.PENDING)
        acknowledged = store.acknowledge(command.command_id, now=now.add_seconds(5))
        self.assertEqual(acknowledged.status, CommandStatus.ACKNOWLEDGED)


if __name__ == "__main__":
    unittest.main()
