"""Provider-neutral secret-reference abstraction.

Contract (see docs/architecture/requirements-addendum.md, "Vault support",
and CLAUDE.md's data-handling rules):

- A raw secret value is accepted exactly once, at :func:`store_secret`, and
  is never returned, logged, or embedded in any object this module
  produces. Only an opaque :class:`SecretReference` is ever handed back.
- ``VaultProvider`` declares its capabilities explicitly; callers must not
  assume a capability a provider did not declare.
- This module performs no network I/O. Anything reaching a real backend
  (HashiCorp Vault via Spring Vault, or a future AWS/Azure/GCP adapter) is
  out of scope for M1 and must live outside this module with its own
  integration tests in an isolated environment.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import re
import uuid
from abc import ABC, abstractmethod
from typing import FrozenSet

_REFERENCE_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")

# Capabilities a provider may declare. A provider must not be invoked for a
# capability it did not declare.
CAPABILITY_WRITE = "write"
CAPABILITY_ROTATE = "rotate"
CAPABILITY_REVOKE = "revoke"
KNOWN_CAPABILITIES = frozenset({CAPABILITY_WRITE, CAPABILITY_ROTATE, CAPABILITY_REVOKE})


@dataclass(frozen=True)
class SecretReference:
    """An opaque handle to a secret. Never carries the secret's value."""

    reference_id: str
    provider_name: str
    scope: str
    created_at: str

    def __post_init__(self) -> None:
        if not _REFERENCE_ID_PATTERN.fullmatch(self.reference_id):
            raise ValueError("Invalid reference id")
        if not self.provider_name:
            raise ValueError("provider_name is required")
        if not self.scope:
            raise ValueError("scope is required")

    def __repr__(self) -> str:  # pragma: no cover - defensive, exercised by tests
        return (
            f"SecretReference(reference_id={self.reference_id!r}, "
            f"provider_name={self.provider_name!r}, scope={self.scope!r})"
        )


class VaultProvider(ABC):
    """Base class for a secrets backend integration.

    Subclasses implement :meth:`_write` only. They must never return the
    raw value from any public method, and must not perform capabilities
    they did not declare in :attr:`capabilities`.
    """

    name: str = "unnamed-provider"
    capabilities: FrozenSet[str] = frozenset()

    def __init__(self) -> None:
        unknown = self.capabilities - KNOWN_CAPABILITIES
        if unknown:
            raise ValueError(f"Unknown capabilities declared: {sorted(unknown)}")

    @abstractmethod
    def _write(self, raw_value: str, *, scope: str) -> str:
        """Persist ``raw_value`` and return a provider-internal opaque id.

        Implementations must not log, cache, or otherwise retain
        ``raw_value`` beyond what the backend call itself requires.
        """

    def store_secret(self, raw_value: str, *, scope: str) -> SecretReference:
        if CAPABILITY_WRITE not in self.capabilities:
            raise PermissionError(f"{self.name} did not declare write capability")
        if not isinstance(raw_value, str) or not raw_value:
            raise ValueError("raw_value must be a non-empty string")
        if not isinstance(scope, str) or not scope:
            raise ValueError("scope is required (environment/tenant/service/integration/device)")
        internal_id = self._write(raw_value, scope=scope)
        if not isinstance(internal_id, str) or not internal_id:
            raise ValueError("Provider must return a non-empty internal id")
        return SecretReference(
            reference_id=str(uuid.uuid4()),
            provider_name=self.name,
            scope=scope,
            created_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        )


class InMemoryVaultProvider(VaultProvider):
    """Test double only. Not a production backend.

    Represents the "isolated test environment" referenced by the M1 gate
    (``docs/test-plan.md``); a real Vault/AWS/Azure/GCP adapter needs its
    own integration tests and is out of scope here.
    """

    name = "in-memory-test-provider"
    capabilities = frozenset({CAPABILITY_WRITE, CAPABILITY_ROTATE, CAPABILITY_REVOKE})

    def __init__(self) -> None:
        super().__init__()
        self._store: dict[str, str] = {}

    def _write(self, raw_value: str, *, scope: str) -> str:
        internal_id = str(uuid.uuid4())
        self._store[internal_id] = raw_value
        return internal_id

    def known_internal_id_count(self) -> int:
        """Test-only introspection; never exposes values."""
        return len(self._store)


def is_secret_like_key(key: str) -> bool:
    """Heuristic used by the migration-studio config lifecycle to refuse
    raw secret values in configuration payloads (see sources.py). This is
    deliberately conservative: it is meant to catch obvious cases, not to
    be the platform's data-classification engine (that is a later
    milestone concern, see docs/contract.md item 9).
    """
    lowered = key.lower()
    return any(token in lowered for token in ("password", "secret", "token", "api_key", "apikey", "credential"))
