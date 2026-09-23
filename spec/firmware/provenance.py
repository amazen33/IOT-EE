"""firmware-management bounded context: firmware artifact provenance
(M5, requirements-addendum.md's "firmware provenance process").

Decision (docs/adr/0006-m5-firmware.md): provenance is build metadata +
content hash + signer identity, verified as a unit -- not signature
verification alone. No real CI/build-system integration exists;
``build_source_ref`` is an opaque, caller-supplied reference string (e.g.
a commit SHA or CI run URL) that this module validates only for
shape/presence, never for authenticity against a real build system.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from firmware.signing import SignatureVerifier

_SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
_VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")


class ProvenanceError(ValueError):
    pass


@dataclass(frozen=True)
class FirmwareArtifact:
    artifact_id: str
    version: str
    content_sha256: str
    signer_id: str
    build_source_ref: str
    signature: str

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.artifact_id):
            raise ProvenanceError("Invalid artifact_id")
        if not _VERSION_PATTERN.fullmatch(self.version):
            raise ProvenanceError(f"version must be semver-shaped (X.Y.Z): '{self.version}'")
        if not _SHA256_PATTERN.fullmatch(self.content_sha256.lower()):
            raise ProvenanceError("content_sha256 must be a 64-character hex SHA-256 digest")
        if not self.signer_id:
            raise ProvenanceError("signer_id is required")
        if not self.build_source_ref:
            raise ProvenanceError("build_source_ref is required")
        if not self.signature:
            raise ProvenanceError("signature is required")


def verify_firmware_provenance(artifact: FirmwareArtifact, *, verifier: SignatureVerifier) -> None:
    """Raises ProvenanceError if the artifact's signature does not verify
    against its declared signer, or if verification itself fails (e.g. an
    unknown signer). Shape validation of the artifact's own fields
    already happened at construction (``__post_init__``); this is the
    cryptographic half, and is required before an artifact may enter a
    ``firmware.rollout.FirmwareRollout`` -- an unverified or tampered
    artifact cannot structurally be rolled out, matching the discipline
    every prior milestone applies to its own invariants."""
    try:
        valid = verifier.verify(
            content_hash=artifact.content_sha256, signature=artifact.signature, signer_id=artifact.signer_id
        )
    except Exception as exc:
        raise ProvenanceError(f"Signature verification failed for artifact '{artifact.artifact_id}': {exc}") from exc
    if not valid:
        raise ProvenanceError(f"Signature does not verify for artifact '{artifact.artifact_id}'")
