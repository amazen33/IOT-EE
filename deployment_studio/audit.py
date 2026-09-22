"""deployment_studio.audit: an append-only audit log for deployment-plan
lifecycle transitions.

Decision (docs/adr/0009-m8-deployment-studio.md): this is a
self-contained shape, structurally similar to M6's evidence records but
deliberately not an import of ``evidence.records`` -- ``deployment_studio``
must not depend on ``evidence`` (see the package docstring and
``scripts/check.py``'s ``_check_deployment_studio_isolation``). Every plan
lifecycle transition (create/validate/approve/reject) is required to
record one of these entries; ``PlanAuditLog`` is write-once per
``entry_id`` and offers no update or delete.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_TIMESTAMP_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")


class PlanAction(str, Enum):
    CREATED = "created"
    VALIDATED = "validated"
    APPROVED = "approved"
    REJECTED = "rejected"


class InvalidAuditEntryError(ValueError):
    pass


class DuplicateAuditEntryError(ValueError):
    pass


@dataclass(frozen=True)
class PlanAuditEntry:
    entry_id: str
    plan_id: str
    tenant_id: str
    action: PlanAction
    actor_principal_id: str
    occurred_at: str
    notes: str | None = None

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.entry_id):
            raise InvalidAuditEntryError("Invalid entry_id")
        if not _ID_PATTERN.fullmatch(self.plan_id):
            raise InvalidAuditEntryError("Invalid plan_id")
        if not _ID_PATTERN.fullmatch(self.tenant_id):
            raise InvalidAuditEntryError("Invalid tenant_id")
        if not isinstance(self.action, PlanAction):
            raise InvalidAuditEntryError("Invalid action")
        if not _ID_PATTERN.fullmatch(self.actor_principal_id):
            raise InvalidAuditEntryError("Invalid actor_principal_id")
        if not _TIMESTAMP_PATTERN.fullmatch(self.occurred_at):
            raise InvalidAuditEntryError("occurred_at must be an ISO-8601 UTC timestamp")


class PlanAuditLog:
    def __init__(self) -> None:
        self._entries: dict[str, PlanAuditEntry] = {}

    def record(self, entry: PlanAuditEntry) -> None:
        if entry.entry_id in self._entries:
            raise DuplicateAuditEntryError(f"Audit entry_id '{entry.entry_id}' already exists (write-once log)")
        self._entries[entry.entry_id] = entry

    def entries_for_plan(self, plan_id: str) -> tuple[PlanAuditEntry, ...]:
        matches = [entry for entry in self._entries.values() if entry.plan_id == plan_id]
        return tuple(sorted(matches, key=lambda entry: entry.occurred_at))
