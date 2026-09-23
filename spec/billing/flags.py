"""billing.flags: the single monetization resolution point.

Decision (docs/adr/0008-m7-monetization.md): one resolver,
``MonetizationFlags.is_monetization_enabled(tenant_id)``, global default
plus per-tenant override, resolved as override-if-set else global
default. Every other function in this package that has a billing side
effect is required to check this first and produce none of that side
effect when it resolves False -- see billing.metering and
billing.service.

This is deliberately the only flag surface in this package. There is no
``is_feature_entitled`` here or anywhere else in ``billing`` -- whether a
feature is *available* is a separate, deferred concern (per-feature
entitlement, out of scope for M7; see docs/billing.md). This resolver
only answers "does usage for this tenant get metered, priced, and
billed," never "can this tenant use this feature."
"""

from __future__ import annotations

import re

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")


class InvalidTenantIdError(ValueError):
    pass


class MonetizationFlags:
    """Holds the global default and any per-tenant overrides. Standing in
    for wherever this is ultimately configured (a settings table, a
    feature-flag service); no real backend exists yet."""

    def __init__(self, *, global_default: bool = False) -> None:
        self._global_default = global_default
        self._tenant_overrides: dict[str, bool] = {}

    @property
    def global_default(self) -> bool:
        return self._global_default

    def set_global_default(self, enabled: bool) -> None:
        self._global_default = enabled

    def set_tenant_override(self, tenant_id: str, enabled: bool) -> None:
        if not _ID_PATTERN.fullmatch(tenant_id):
            raise InvalidTenantIdError("Invalid tenant_id")
        self._tenant_overrides[tenant_id] = enabled

    def clear_tenant_override(self, tenant_id: str) -> None:
        self._tenant_overrides.pop(tenant_id, None)

    def has_tenant_override(self, tenant_id: str) -> bool:
        return tenant_id in self._tenant_overrides

    def is_monetization_enabled(self, tenant_id: str) -> bool:
        """Resolution order: an explicit per-tenant override wins;
        otherwise the global default applies. This is the only function
        in this package any caller outside it should need to check."""
        if not _ID_PATTERN.fullmatch(tenant_id):
            raise InvalidTenantIdError("Invalid tenant_id")
        return self._tenant_overrides.get(tenant_id, self._global_default)
