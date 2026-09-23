import unittest

from firmware.signing import InMemorySignatureVerifier, UnknownSignerError


class InMemorySignatureVerifierTests(unittest.TestCase):
    def test_sign_then_verify_round_trips(self):
        verifier = InMemorySignatureVerifier()
        verifier.register_signer("synthetic-signer-001", "synthetic-shared-secret-001")
        signature = verifier.sign(content_hash="a" * 64, signer_id="synthetic-signer-001")
        self.assertTrue(verifier.verify(content_hash="a" * 64, signature=signature, signer_id="synthetic-signer-001"))

    def test_verify_rejects_wrong_signature(self):
        verifier = InMemorySignatureVerifier()
        verifier.register_signer("synthetic-signer-001", "synthetic-shared-secret-001")
        self.assertFalse(
            verifier.verify(content_hash="a" * 64, signature="0" * 64, signer_id="synthetic-signer-001")
        )

    def test_verify_rejects_signature_for_different_content(self):
        verifier = InMemorySignatureVerifier()
        verifier.register_signer("synthetic-signer-001", "synthetic-shared-secret-001")
        signature = verifier.sign(content_hash="a" * 64, signer_id="synthetic-signer-001")
        self.assertFalse(
            verifier.verify(content_hash="b" * 64, signature=signature, signer_id="synthetic-signer-001")
        )

    def test_unknown_signer_raises_on_verify(self):
        verifier = InMemorySignatureVerifier()
        with self.assertRaises(UnknownSignerError):
            verifier.verify(content_hash="a" * 64, signature="0" * 64, signer_id="synthetic-signer-unknown")

    def test_unknown_signer_raises_on_sign(self):
        verifier = InMemorySignatureVerifier()
        with self.assertRaises(UnknownSignerError):
            verifier.sign(content_hash="a" * 64, signer_id="synthetic-signer-unknown")

    def test_register_signer_requires_both_fields(self):
        verifier = InMemorySignatureVerifier()
        with self.assertRaises(ValueError):
            verifier.register_signer("", "synthetic-shared-secret-001")
        with self.assertRaises(ValueError):
            verifier.register_signer("synthetic-signer-001", "")

    def test_two_different_signers_produce_different_signatures(self):
        verifier = InMemorySignatureVerifier()
        verifier.register_signer("synthetic-signer-001", "synthetic-shared-secret-001")
        verifier.register_signer("synthetic-signer-002", "synthetic-shared-secret-002")
        sig1 = verifier.sign(content_hash="a" * 64, signer_id="synthetic-signer-001")
        sig2 = verifier.sign(content_hash="a" * 64, signer_id="synthetic-signer-002")
        self.assertNotEqual(sig1, sig2)
        self.assertFalse(verifier.verify(content_hash="a" * 64, signature=sig1, signer_id="synthetic-signer-002"))


if __name__ == "__main__":
    unittest.main()
