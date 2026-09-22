import unittest

from domain_core.assets import (
    AssetHierarchy,
    CommunicationType,
    CrossTenantRelationError,
    Region,
    Site,
    Tank,
    TankMaterial,
)


class AssetHierarchyTests(unittest.TestCase):
    def setUp(self):
        self.hierarchy = AssetHierarchy()
        self.region = self.hierarchy.add_region(Region(region_id="synthetic-region-1", tenant_id="synthetic-tenant-a", name="North"))
        self.site = self.hierarchy.add_site(
            Site(site_id="synthetic-site-1", tenant_id="synthetic-tenant-a", region_id="synthetic-region-1", name="Depot 1")
        )

    def test_add_tank_under_matching_tenant_succeeds(self):
        tank = self.hierarchy.add_tank(
            Tank(
                tank_id="synthetic-tank-1",
                tenant_id="synthetic-tenant-a",
                site_id="synthetic-site-1",
                material=TankMaterial.STEEL,
                communication_type=CommunicationType.CELLULAR,
            )
        )
        self.assertEqual(self.hierarchy.get_tank("synthetic-tank-1"), tank)
        self.assertEqual(self.hierarchy.tanks_for_tenant("synthetic-tenant-a"), [tank])

    def test_site_cross_tenant_relation_rejected(self):
        with self.assertRaises(CrossTenantRelationError):
            self.hierarchy.add_site(
                Site(site_id="synthetic-site-2", tenant_id="synthetic-tenant-b", region_id="synthetic-region-1", name="Sneaky")
            )

    def test_tank_cross_tenant_relation_rejected(self):
        with self.assertRaises(CrossTenantRelationError):
            self.hierarchy.add_tank(
                Tank(
                    tank_id="synthetic-tank-2",
                    tenant_id="synthetic-tenant-b",
                    site_id="synthetic-site-1",
                    material=TankMaterial.STEEL,
                    communication_type=CommunicationType.WIFI,
                )
            )

    def test_tank_under_unknown_site_rejected(self):
        with self.assertRaises(ValueError):
            self.hierarchy.add_tank(
                Tank(
                    tank_id="synthetic-tank-3",
                    tenant_id="synthetic-tenant-a",
                    site_id="synthetic-site-does-not-exist",
                    material=TankMaterial.CONCRETE,
                    communication_type=CommunicationType.LORAWAN,
                )
            )

    def test_duplicate_ids_rejected(self):
        with self.assertRaises(ValueError):
            self.hierarchy.add_region(Region(region_id="synthetic-region-1", tenant_id="synthetic-tenant-a", name="Dup"))

    def test_tanks_for_tenant_excludes_other_tenants(self):
        self.hierarchy.add_tank(
            Tank(
                tank_id="synthetic-tank-4",
                tenant_id="synthetic-tenant-a",
                site_id="synthetic-site-1",
                material=TankMaterial.OTHER,
                communication_type=CommunicationType.SATELLITE,
            )
        )
        self.assertEqual(self.hierarchy.tanks_for_tenant("synthetic-tenant-zzz"), [])

    def test_tank_rejects_non_dict_geometry(self):
        with self.assertRaises(ValueError):
            Tank(
                tank_id="synthetic-tank-5",
                tenant_id="synthetic-tenant-a",
                site_id="synthetic-site-1",
                material=TankMaterial.STEEL,
                communication_type=CommunicationType.CELLULAR,
                geometry="not-a-dict",
            )


if __name__ == "__main__":
    unittest.main()
