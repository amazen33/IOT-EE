"""Migration Studio source-registration configuration lifecycle.

Implements the M1 slice of "Version, validate, preview, approve, and roll
back configurations" (requirements-addendum.md, "Interfaces and migration
sequence"). Explicitly out of scope here (non-goals, see
docs/migration-studio.md): actually connecting to a legacy system, actually
reading Mosquitto/PostgreSQL/ThingsBoard data, and any "controlled
application"/cutover step (M3 for connectors/shadow-parity tooling, M9 for
full production migration and cutover).

Every state transition is recorded to an evidence log (see evidence.py) and
redacts any configuration value that looks like a secret before recording
it — the caller must have already turned real secrets into
``vault.SecretReference`` objects.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
import re
from typing import Any, Mapping

from migration_studio.vault import SecretReference, is_secret_like_key
from migration_studio.evidence import EvidenceLog

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")


class SourceType(str, Enum):
    MOSQUITTO_MIRROR = "mosquitto_mirror"
    POSTGRES_HISTORY = "postgres_history"
    THINGSBOARD = "thingsboard"
    REPORTS = "reports"


class RegistrationStatus(str, Enum):
    DRAFT = "draft"
    VALIDATED = "validated"
    PREVIEWED = "previewed"
    APPROVED = "approved"
    REJECTED = "rejected"
    ROLLED_BACK = "rolled_back"


# Explicit allow-list of transitions. Anything not listed here is refused.
# "applied"/cutover is intentionally absent: it is not implemented in M1.
_ALLOWED_TRANSITIONS = {
    (RegistrationStatus.DRAFT, RegistrationStatus.VALIDATED),
    (RegistrationStatus.VALIDATED, RegistrationStatus.PREVIEWED),
    (RegistrationStatus.VALIDATED, RegistrationStatus.REJECTED),
    (RegistrationStatus.PREVIEWED, RegistrationStatus.APPROVED),
    (RegistrationStatus.PREVIEWED, RegistrationStatus.REJECTED),
    (RegistrationStatus.APPROVED, RegistrationStatus.ROLLED_BACK),
}


def _redact(config: Mapping[str, Any]) -> dict:
    """Return a copy of ``config`` safe to write to evidence/logs.

    Any key that looks like a secret must already be a SecretReference;
    anything else raises rather than silently redacting, so a raw secret
    can never be accepted into a registration in the first place.
    """
    redacted: dict[str, Any] = {}
    for key, value in config.items():
        if is_secret_like_key(key):
            if not isinstance(value, SecretReference):
                raise ValueError(
                    f"Field '{key}' looks like a secret and must be a SecretReference, "
                    "not a raw value"
                )
            redacted[key] = {"secret_reference": value.reference_id, "provider": value.provider_name}
        elif isinstance(value, SecretReference):
            redacted[key] = {"secret_reference": value.reference_id, "provider": value.provider_name}
        else:
            redacted[key] = value
    return redacted


@dataclass
class SourceRegistration:
    source_id: str
    source_type: SourceType
    config: dict = field(default_factory=dict)
    status: RegistrationStatus = RegistrationStatus.DRAFT
    version: int = 1

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.source_id):
            raise ValueError("Invalid source_id")
        if not isinstance(self.source_type, SourceType):
            raise ValueError("Invalid source_type")
        # Fails fast (and loudly) on any raw secret smuggled into config.
        _redact(self.config)


class InvalidTransition(ValueError):
    pass


class SourceRegistry:
    """In-memory registry for source registrations and their lifecycle.

    Dry-run guarantee: ``validate`` and ``preview`` never mutate the
    registration's ``config`` and never perform I/O against an external
    system — they only inspect the in-memory configuration already
    supplied at registration time.
    """

    def __init__(self, evidence_log: EvidenceLog | None = None) -> None:
        self._registrations: dict[str, SourceRegistration] = {}
        self.evidence = evidence_log if evidence_log is not None else EvidenceLog()

    def register(self, source_id: str, source_type: SourceType, config: Mapping[str, Any]) -> SourceRegistration:
        if source_id in self._registrations:
            raise ValueError(f"Source '{source_id}' is already registered")
        registration = SourceRegistration(source_id=source_id, source_type=source_type, config=dict(config))
        self._registrations[source_id] = registration
        self.evidence.record(
            actor="system",
            action="register",
            source_id=source_id,
            from_status=None,
            to_status=registration.status.value,
            config_snapshot=_redact(registration.config),
        )
        return registration

    def get(self, source_id: str) -> SourceRegistration:
        return self._registrations[source_id]

    def _transition(self, source_id: str, actor: str, action: str, to_status: RegistrationStatus) -> SourceRegistration:
        registration = self.get(source_id)
        pair = (registration.status, to_status)
        if pair not in _ALLOWED_TRANSITIONS:
            raise InvalidTransition(
                f"Cannot move source '{source_id}' from {registration.status.value} to {to_status.value}"
            )
        from_status = registration.status
        registration.status = to_status
        registration.version += 1
        self.evidence.record(
            actor=actor,
            action=action,
            source_id=source_id,
            from_status=from_status.value,
            to_status=to_status.value,
            config_snapshot=_redact(registration.config),
        )
        return registration

    def validate(self, source_id: str, *, actor: str = "system") -> SourceRegistration:
        """Dry run only: checks configuration shape, mutates no external
        state, and only moves DRAFT -> VALIDATED."""
        registration = self.get(source_id)
        if not registration.config:
            raise ValueError("Cannot validate an empty configuration")
        return self._transition(source_id, actor, "validate", RegistrationStatus.VALIDATED)

    def preview(self, source_id: str, *, actor: str = "system") -> SourceRegistration:
        """Dry run only: VALIDATED -> PREVIEWED. Field/formula mapping
        preview is a later-milestone concern; this only records the
        lifecycle step."""
        return self._transition(source_id, actor, "preview", RegistrationStatus.PREVIEWED)

    def approve(self, source_id: str, *, actor: str) -> SourceRegistration:
        if not actor:
            raise ValueError("approve requires a named human actor")
        return self._transition(source_id, actor, "approve", RegistrationStatus.APPROVED)

    def reject(self, source_id: str, *, actor: str, reason: str) -> SourceRegistration:
        if not reason:
            raise ValueError("reject requires a reason")
        registration = self._transition(source_id, actor, "reject", RegistrationStatus.REJECTED)
        self.evidence.record(
            actor=actor,
            action="reject-reason",
            source_id=source_id,
            from_status=RegistrationStatus.REJECTED.value,
            to_status=RegistrationStatus.REJECTED.value,
            config_snapshot={"reason": reason},
        )
        return registration

    def roll_back(self, source_id: str, *, actor: str, reason: str) -> SourceRegistration:
        if not reason:
            raise ValueError("roll_back requires a reason")
        registration = self._transition(source_id, actor, "roll_back", RegistrationStatus.ROLLED_BACK)
        self.evidence.record(
            actor=actor,
            action="roll_back-reason",
            source_id=source_id,
            from_status=RegistrationStatus.ROLLED_BACK.value,
            to_status=RegistrationStatus.ROLLED_BACK.value,
            config_snapshot={"reason": reason},
        )
        return registration
