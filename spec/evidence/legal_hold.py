"""evidence.legal_hold: RBAC-gated legal-hold flag.

Decision (docs/adr/0007-m6-evidence-resilience-dr.md): a legal hold is an
authorization-gated operation, not a bare store method any caller can
invoke. ``evidence.worm.WormStore.expire`` already refuses unconditionally
while a record's ``legal_hold`` flag is set (see worm.py); this module is
the only place that flag is meant to be flipped, and it requires the new
``evidence.legal_hold`` permission (added to domain_core.rbac's
PERMISSION_CATALOG by this milestone, following the established pattern
of extending the catalog as new capabilities are needed).
"""

from __future__ import annotations

from domain_core.rbac import Principal
from evidence.records import EvidenceRecord
from evidence.worm import WormStore

_PERMISSION = "evidence.legal_hold"


class LegalHoldPermissionError(PermissionError):
    pass


def set_legal_hold(*, principal: Principal, store: WormStore, record_id: str, held: bool) -> EvidenceRecord:
    if not principal.has_permission(_PERMISSION):
        raise LegalHoldPermissionError(f"Principal lacks '{_PERMISSION}'")
    return store.set_legal_hold(record_id, held=held)
