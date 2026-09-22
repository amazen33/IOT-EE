import json
from pathlib import Path
import unittest

from adapters.worm_s3.store import S3WormStore
from evidence.records import EvidenceCategory, build_evidence_record

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "adapters_worm_s3.synthetic.json"


class AdaptersWormS3FixtureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(FIXTURE_PATH.read_text())

    def test_fixture_ids_are_synthetic(self):
        for entry in self.fixture["records"]:
            self.assertTrue(entry["record_id"].startswith("synthetic-"))
            self.assertTrue(entry["tenant_id"].startswith("synthetic-"))

    def test_fixture_env_var_names_documented(self):
        self.assertEqual(
            set(self.fixture["config_env_vars"]),
            {"bucket", "endpoint_url", "region"},
        )

    def test_all_fixture_records_construct_and_roundtrip_against_a_fake_client(self):
        from tests.test_adapters_worm_s3_contract import FakeS3Client

        store = S3WormStore(bucket="synthetic-test-bucket", client=FakeS3Client())
        for entry in self.fixture["records"]:
            record = build_evidence_record(
                record_id=entry["record_id"],
                tenant_id=entry["tenant_id"],
                category=EvidenceCategory(entry["category"]),
                occurred_at=entry["occurred_at"],
                payload=entry["payload"],
                retention_until=entry["retention_until"],
            )
            store.put(record)
            self.assertEqual(store.get(record.record_id), record)


if __name__ == "__main__":
    unittest.main()
