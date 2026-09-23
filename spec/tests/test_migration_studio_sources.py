import json
import unittest
from pathlib import Path

from migration_studio.evidence import EvidenceLog
from migration_studio.sources import (
    InvalidTransition,
    RegistrationStatus,
    SourceRegistry,
    SourceType,
)
from migration_studio.vault import InMemoryVaultProvider

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PATH = ROOT / "fixtures" / "migration_sources.synthetic.json"


class SourceRegistryTests(unittest.TestCase):
    def setUp(self):
        self.registry = SourceRegistry()
        self.provider = InMemoryVaultProvider()

    def test_register_starts_in_draft_and_records_evidence(self):
        reg = self.registry.register(
            "synthetic-source-001", SourceType.MOSQUITTO_MIRROR, {"host": "synthetic.local"}
        )
        self.assertEqual(reg.status, RegistrationStatus.DRAFT)
        self.assertEqual(len(self.registry.evidence), 1)

    def test_duplicate_registration_rejected(self):
        self.registry.register("dup-source", SourceType.REPORTS, {"a": 1})
        with self.assertRaises(ValueError):
            self.registry.register("dup-source", SourceType.REPORTS, {"a": 1})

    def test_happy_path_lifecycle(self):
        self.registry.register("hp-source", SourceType.POSTGRES_HISTORY, {"schema": "tank_history"})
        self.registry.validate("hp-source")
        self.registry.preview("hp-source")
        reg = self.registry.approve("hp-source", actor="alice@example.com")
        self.assertEqual(reg.status, RegistrationStatus.APPROVED)
        self.registry.roll_back("hp-source", actor="alice@example.com", reason="found a mapping error")
        self.assertEqual(self.registry.get("hp-source").status, RegistrationStatus.ROLLED_BACK)
        self.assertTrue(self.registry.evidence.verify())

    def test_reject_path_requires_reason(self):
        self.registry.register("rej-source", SourceType.THINGSBOARD, {"tenant": "acme"})
        self.registry.validate("rej-source")
        self.registry.preview("rej-source")
        with self.assertRaises(ValueError):
            self.registry.reject("rej-source", actor="bob@example.com", reason="")
        self.registry.reject("rej-source", actor="bob@example.com", reason="mapping incomplete")
        self.assertEqual(self.registry.get("rej-source").status, RegistrationStatus.REJECTED)

    def test_invalid_transitions_are_rejected(self):
        self.registry.register("bad-source", SourceType.MOSQUITTO_MIRROR, {"host": "x"})
        # Cannot skip straight to approved.
        with self.assertRaises(InvalidTransition):
            self.registry.approve("bad-source", actor="carol@example.com")
        # Cannot roll back before ever being approved.
        with self.assertRaises(InvalidTransition):
            self.registry.roll_back("bad-source", actor="carol@example.com", reason="oops")
        self.registry.validate("bad-source")
        # Cannot validate twice.
        with self.assertRaises(InvalidTransition):
            self.registry.validate("bad-source")

    def test_approve_requires_named_actor(self):
        self.registry.register("actor-source", SourceType.REPORTS, {"a": 1})
        self.registry.validate("actor-source")
        self.registry.preview("actor-source")
        with self.assertRaises(ValueError):
            self.registry.approve("actor-source", actor="")

    def test_validate_is_dry_run_and_does_not_mutate_config(self):
        original_config = {"host": "synthetic.local", "port": 1883}
        self.registry.register("dryrun-source", SourceType.MOSQUITTO_MIRROR, dict(original_config))
        before = dict(self.registry.get("dryrun-source").config)
        self.registry.validate("dryrun-source")
        self.registry.preview("dryrun-source")
        after = dict(self.registry.get("dryrun-source").config)
        self.assertEqual(before, after)
        self.assertEqual(after, original_config)

    def test_cannot_validate_empty_configuration(self):
        self.registry.register("empty-source", SourceType.REPORTS, {})
        with self.assertRaises(ValueError):
            self.registry.validate("empty-source")

    def test_raw_secret_in_config_is_rejected(self):
        with self.assertRaises(ValueError):
            self.registry.register(
                "secret-leak-source",
                SourceType.THINGSBOARD,
                {"password": "raw-secret-value"},
            )

    def test_secret_reference_in_config_is_accepted_and_redacted_in_evidence(self):
        reference = self.provider.store_secret("raw-secret-value", scope="tenant:acme")
        self.registry.register(
            "secret-ok-source",
            SourceType.THINGSBOARD,
            {"password": reference, "host": "synthetic.local"},
        )
        for record in self.registry.evidence:
            snapshot_text = str(record.config_snapshot)
            self.assertNotIn("raw-secret-value", snapshot_text)

    def test_no_application_or_cutover_capability_exists(self):
        # M1 non-goal: there must be no way to reach an "applied"/cutover
        # state from this module at all.
        applied_like = {status for status in RegistrationStatus if status.value in ("applied", "cutover")}
        self.assertEqual(applied_like, set())

    def test_evidence_chain_is_tamper_evident(self):
        self.registry.register("tamper-source", SourceType.REPORTS, {"a": 1})
        log = self.registry.evidence
        self.assertTrue(log.verify())
        # Directly corrupt a record in the underlying list to prove verify() catches it.
        records = list(log)
        object.__setattr__(records[0], "action", "tampered")
        log._records[0] = records[0]  # noqa: SLF001 - whitebox test of tamper detection
        self.assertFalse(log.verify())


class SynthethicFixtureTests(unittest.TestCase):
    """Exercises the registry against the synthetic fixture file so the
    fixture is validated, not just decorative (mirrors how
    scripts/check.py validates fixtures/telemetry.synthetic.json)."""

    def test_all_synthetic_sources_register_and_reach_validated(self):
        entries = json.loads(FIXTURE_PATH.read_text())
        self.assertTrue(entries, "synthetic fixture must not be empty")
        registry = SourceRegistry()
        for entry in entries:
            self.assertTrue(entry["source_id"].startswith("synthetic-"), "fixture ids must be synthetic")
            registry.register(entry["source_id"], SourceType(entry["source_type"]), entry["config"])
            registry.validate(entry["source_id"])
        self.assertEqual(len(registry.evidence), len(entries) * 2)
        self.assertTrue(registry.evidence.verify())


if __name__ == "__main__":
    unittest.main()
