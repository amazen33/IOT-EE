import copy
import json
from pathlib import Path
import unittest

from foundation.contracts import validate_event, validate_plan

ROOT = Path(__file__).resolve().parents[1]


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.event = json.loads((ROOT / "fixtures/telemetry.synthetic.json").read_text())[0]
        self.tenant = self.event["tenant_id"]

    def validate(self, event):
        return validate_event(event, authorized_tenant=self.tenant)

    def test_valid_and_stable_identity(self):
        self.assertEqual(self.validate(self.event), self.validate(copy.deepcopy(self.event)))
        self.assertEqual(self.validate(self.event), (self.tenant, "synthetic-event-001"))

    def test_tenant_boundary(self):
        for tenant in (None, "", "synthetic-tenant-other"):
            with self.subTest(tenant=tenant), self.assertRaises(ValueError):
                validate_event(self.event, authorized_tenant=tenant)

    def test_tenant_scopes_identity(self):
        other = copy.deepcopy(self.event)
        other["tenant_id"] = "synthetic-tenant-b"
        self.assertNotEqual(self.validate(self.event), validate_event(other, authorized_tenant=other["tenant_id"]))

    def test_unknown_fields_rejected_without_echo(self):
        for container in (self.event, self.event["payload"]):
            container["password"] = "synthetic-sensitive-sentinel"
            with self.assertRaises(ValueError) as error:
                self.validate(self.event)
            self.assertNotIn("synthetic-sensitive-sentinel", str(error.exception))
            del container["password"]

    def test_missing_fields(self):
        for field in self.event:
            candidate = copy.deepcopy(self.event)
            del candidate[field]
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(candidate)

    def test_bad_envelopes(self):
        for field, values in {"schema_version": [True, 2, "1"],
                              "event_type": ["UsageMetered", None],
                              "event_id": ["", "has space", "x" * 129, None],
                              "occurred_at": ["2026-02-30T00:00:00Z", "2026-01-01", None],
                              "payload": [None, [], {}]}.items():
            for value in values:
                candidate = copy.deepcopy(self.event)
                candidate[field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    self.validate(candidate)

    def test_invalid_measurements(self):
        for field in self.event["payload"]:
            values = [True, "10", None, float("nan"), float("inf")]
            values += [-1, 101] if field.endswith("_percent") else [-274]
            for value in values:
                candidate = copy.deepcopy(self.event)
                candidate["payload"][field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    self.validate(candidate)

    def test_percentage_boundaries(self):
        for value in (0, 100):
            self.event["payload"]["level_percent"] = value
            self.event["payload"]["battery_percent"] = value
            self.validate(self.event)

    def test_inert_plan(self):
        plan = json.loads((ROOT / "deployment/m0-plan.json").read_text())
        validate_plan(plan)
        for field, value in [("provisioning_enabled", True), ("legacy_import_enabled", True),
                             ("resources", ["vm"]), ("secret_refs", ["secret"]),
                             ("schema_version", True), ("provisioning_enabled", 0),
                             ("command", "apply"), ("stage", "M8")]:
            candidate = dict(plan, **{field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_plan(candidate)


if __name__ == "__main__":
    unittest.main()
