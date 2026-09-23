import unittest

from migration_studio.evidence import EvidenceLog


class EvidenceLogTests(unittest.TestCase):
    def test_empty_log_verifies(self):
        self.assertTrue(EvidenceLog().verify())

    def test_append_only_records_have_hash_chain(self):
        log = EvidenceLog()
        first = log.record(
            actor="system", action="register", source_id="s1",
            from_status=None, to_status="draft", config_snapshot={"a": 1},
        )
        second = log.record(
            actor="system", action="validate", source_id="s1",
            from_status="draft", to_status="validated", config_snapshot={"a": 1},
        )
        self.assertEqual(first.previous_hash, "0" * 64)
        self.assertEqual(second.previous_hash, first.record_hash)
        self.assertTrue(log.verify())

    def test_record_requires_actor_action_source_id(self):
        log = EvidenceLog()
        with self.assertRaises(ValueError):
            log.record(actor="", action="x", source_id="s1", from_status=None, to_status="draft", config_snapshot={})
        with self.assertRaises(ValueError):
            log.record(actor="a", action="", source_id="s1", from_status=None, to_status="draft", config_snapshot={})
        with self.assertRaises(ValueError):
            log.record(actor="a", action="x", source_id="", from_status=None, to_status="draft", config_snapshot={})

    def test_tampering_with_a_record_is_detected(self):
        log = EvidenceLog()
        log.record(actor="a", action="register", source_id="s1", from_status=None, to_status="draft", config_snapshot={"a": 1})
        log.record(actor="a", action="validate", source_id="s1", from_status="draft", to_status="validated", config_snapshot={"a": 1})
        self.assertTrue(log.verify())
        object.__setattr__(log._records[0], "to_status", "approved")  # noqa: SLF001
        self.assertFalse(log.verify())

    def test_reordering_records_is_detected(self):
        log = EvidenceLog()
        log.record(actor="a", action="register", source_id="s1", from_status=None, to_status="draft", config_snapshot={})
        log.record(actor="a", action="validate", source_id="s1", from_status="draft", to_status="validated", config_snapshot={})
        log._records.reverse()  # noqa: SLF001
        self.assertFalse(log.verify())

    def test_config_snapshot_never_carries_raw_looking_secret_when_caller_redacts(self):
        log = EvidenceLog()
        record = log.record(
            actor="a", action="register", source_id="s1", from_status=None, to_status="draft",
            config_snapshot={"password": {"secret_reference": "ref-1", "provider": "in-memory-test-provider"}},
        )
        self.assertNotIn("raw", str(record.config_snapshot))
        self.assertEqual(record.config_snapshot["password"]["secret_reference"], "ref-1")


if __name__ == "__main__":
    unittest.main()
