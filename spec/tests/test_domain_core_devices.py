import unittest

from domain_core.devices import Device, DeviceId, DeviceRegistry, DeviceStatus


class DeviceTests(unittest.TestCase):
    def test_generated_device_id_is_valid_uuid_scoped_to_tenant(self):
        device_id = DeviceId.generate("synthetic-tenant-a")
        self.assertEqual(device_id.tenant_id, "synthetic-tenant-a")
        DeviceId(tenant_id="synthetic-tenant-a", value=device_id.value)  # re-validates

    def test_generated_ids_are_unique(self):
        a = DeviceId.generate("synthetic-tenant-a")
        b = DeviceId.generate("synthetic-tenant-a")
        self.assertNotEqual(a.value, b.value)

    def test_malformed_uuid_rejected(self):
        with self.assertRaises(ValueError):
            DeviceId(tenant_id="synthetic-tenant-a", value="not-a-uuid")

    def test_registry_scopes_lookups_by_tenant(self):
        registry = DeviceRegistry()
        device_id = DeviceId.generate("synthetic-tenant-a")
        device = registry.register(Device(device_id=device_id, tank_id="synthetic-tank-1", label="Tank 1 sensor"))

        found = registry.get("synthetic-tenant-a", device_id.value)
        self.assertEqual(found, device)

        with self.assertRaises(KeyError):
            registry.get("synthetic-tenant-b", device_id.value)

    def test_duplicate_registration_rejected(self):
        registry = DeviceRegistry()
        device_id = DeviceId.generate("synthetic-tenant-a")
        registry.register(Device(device_id=device_id, tank_id="synthetic-tank-1", label="First"))
        with self.assertRaises(ValueError):
            registry.register(Device(device_id=device_id, tank_id="synthetic-tank-1", label="Duplicate"))

    def test_devices_for_tenant_excludes_others(self):
        registry = DeviceRegistry()
        registry.register(Device(device_id=DeviceId.generate("synthetic-tenant-a"), tank_id="t1", label="A"))
        registry.register(Device(device_id=DeviceId.generate("synthetic-tenant-b"), tank_id="t2", label="B"))
        self.assertEqual(len(registry.devices_for_tenant("synthetic-tenant-a")), 1)
        self.assertEqual(len(registry.devices_for_tenant("synthetic-tenant-b")), 1)

    def test_device_status_default_and_validation(self):
        device = Device(device_id=DeviceId.generate("synthetic-tenant-a"), tank_id="t1", label="A")
        self.assertEqual(device.status, DeviceStatus.REGISTERED)
        with self.assertRaises(ValueError):
            Device(device_id=DeviceId.generate("synthetic-tenant-a"), tank_id="", label="A")


if __name__ == "__main__":
    unittest.main()
