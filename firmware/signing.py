"""firmware-management bounded context: provider-neutral signature
verification (M5, requirements-addendum.md's "device signing/trust").

Decision (docs/adr/0006-m5-firmware.md): real HSM/KMS/secure-boot
integration is deferred -- this module defines an abstract
``SignatureVerifier``, the same provider-neutral shape as every prior
milestone's provider abstraction (``migration_studio.vault.VaultProvider``,
``ingestion.kafka.KafkaPublisher``, ``gateway.auth.AuthProvider``), plus an
in-memory test double using HMAC-SHA256 over a synthetic shared secret --
not a real asymmetric signature scheme. A real device secure-boot chain
or HSM-backed signing key never exists in this module.
"""

from __future__ import annotations

import hashlib
import hmac
from abc import ABC, abstractmethod


class UnknownSignerError(ValueError):
    pass


class SignatureVerifier(ABC):
    """Provider-neutral signature verification. A real adapter (HSM/KMS
    client verifying an asymmetric signature against a provisioned public
    key) is future integration work with its own tests; this module never
    contacts a real key-management service."""

    @abstractmethod
    def verify(self, *, content_hash: str, signature: str, signer_id: str) -> bool:
        ...


class InMemorySignatureVerifier(SignatureVerifier):
    """Test double only. Registers a synthetic ``signer_id`` ->
    shared-secret "key material" and verifies an HMAC-SHA256 signature
    over the artifact's content hash -- standing in for real asymmetric
    signature verification against an HSM/KMS-issued key. ``sign`` is a
    test/fixture helper only: a real deployment computes the signature
    externally (inside the vault/HSM), and this module only ever
    verifies, never signs, in production use.
    """

    def __init__(self) -> None:
        self._trusted_signers: dict[str, str] = {}

    def register_signer(self, signer_id: str, key_material: str) -> None:
        if not signer_id or not key_material:
            raise ValueError("signer_id and key_material are required")
        self._trusted_signers[signer_id] = key_material

    def sign(self, *, content_hash: str, signer_id: str) -> str:
        """Test/fixture helper: produces a signature a real signer with
        this key material would produce. Never used outside tests and
        fixture generation."""
        key_material = self._require_signer(signer_id)
        return hmac.new(key_material.encode("utf-8"), content_hash.encode("utf-8"), hashlib.sha256).hexdigest()

    def verify(self, *, content_hash: str, signature: str, signer_id: str) -> bool:
        key_material = self._require_signer(signer_id)
        expected = hmac.new(key_material.encode("utf-8"), content_hash.encode("utf-8"), hashlib.sha256).hexdigest()
        return hmac.compare_digest(expected, signature)

    def _require_signer(self, signer_id: str) -> str:
        key_material = self._trusted_signers.get(signer_id)
        if key_material is None:
            raise UnknownSignerError(f"'{signer_id}' is not a trusted signer")
        return key_material
