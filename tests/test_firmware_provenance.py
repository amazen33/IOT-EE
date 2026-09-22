import json
from pathlib import Path
import unittest

from firmware.provenance import FirmwareArtifact, ProvenanceError, verify_firmware_provenance
from firmware.signing import InMemorySignatureVerifier

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "firmware_artifacts.synthetic.json"


def _valid_artifact(**overrides):
    base = dict(
        artifact_id="synthetic-firmware-001",
        version="1.2.3",
        content_sha256="5a413f02e1b4993a25a0bb04795d3539ff2e9b1ab17ae7d68607c39618b2933c",
        signer_id="synthetic-signer-001",
        build_source_ref="git:synthetic-commit-abc123",
        signature="7d695edc9baad624b79c68c5dd8df6a3964957db202af8c16ec815b53102137c",
    )
    base.update(overrides)
    return FirmwareArtifact(**base)


def _verifier():
    verifier = InMemorySignatureVerifier()
    verifier.register_signer("synthetic-signer-001", "synthetic-shared-secret-001")
    return verifier


class FirmwareArtifactValidationTests(unittest.TestCase):
    def test_valid_artifact_constructs(self):
        artifact = _valid_artifact()
        self.assertEqual(artifact.version, "1.2.3")

    def test_rejects_non_semver_version(self):
        with self.assertRaises(ProvenanceError):
            _valid_artifact(version="v1.2")

    def test_rejects_short_hash(self):
        with self.assertRaises(ProvenanceError):
            _valid_artifact(content_sha256="abc123")

    def test_rejects_non_hex_hash(self):
        with self.assertRaises(ProvenanceError):
            _valid_artifact(content_sha256="z" * 64)

    def test_rejects_missing_build_source_ref(self):
        with self.assertRaises(ProvenanceError):
            _valid_artifact(build_source_ref="")

    def test_rejects_missing_signature(self):
        with self.assertRaises(ProvenanceError):
            _valid_artifact(signature="")

    def test_rejects_invalid_artifact_id(self):
        with self.assertRaises(ProvenanceError):
            _valid_artifact(artifact_id="bad id with spaces")


class VerifyFirmwareProvenanceTests(unittest.TestCase):
    def test_valid_artifact_verifies(self):
        verify_firmware_provenance(_valid_artifact(), verifier=_verifier())  # must not raise

    def test_tampered_signature_is_rejected(self):
        artifact = _valid_artifact(signature="0" * 64)
        with self.assertRaises(ProvenanceError):
            verify_firmware_provenance(artifact, verifier=_verifier())

    def test_tampered_content_hash_is_rejected(self):
        # Same signature, but the hash it was computed over has changed --
        # models detecting a swapped firmware image with a copied signature.
        artifact = _valid_artifact(content_sha256="b" * 64)
        with self.assertRaises(ProvenanceError):
            verify_firmware_provenance(artifact, verifier=_verifier())

    def test_unknown_signer_is_rejected(self):
        artifact = _valid_artifact(signer_id="synthetic-signer-untrusted")
        with self.assertRaises(ProvenanceError):
            verify_firmware_provenance(artifact, verifier=_verifier())


class SyntheticFixtureTests(unittest.TestCase):
    """Real HSM/KMS-backed signature verification and real build-system
    provenance are blocked (see docs/firmware.md); this exercises the
    provenance check end-to-end against synthetic fixture artifacts only."""

    def setUp(self):
        self.fixture = json.loads(FIXTURE_PATH.read_text())
        self.verifier = InMemorySignatureVerifier()
        self.verifier.register_signer(self.fixture["signer_id"], self.fixture["signer_key_material"])

    def test_fixture_ids_are_synthetic(self):
        for entry in self.fixture["artifacts"]:
            self.assertTrue(entry["artifact_id"].startswith("synthetic-"))

    def test_all_fixture_artifacts_verify(self):
        for entry in self.fixture["artifacts"]:
            artifact = FirmwareArtifact(**entry)
            verify_firmware_provenance(artifact, verifier=self.verifier)  # must not raise


if __name__ == "__main__":
    unittest.main()
