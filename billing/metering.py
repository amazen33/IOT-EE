"""billing.metering: certified device-count usage per tenant per period.

Decision (docs/adr/0008-m7-monetization.md): the billable usage
dimension is certified device-count, reusing M2's
``domain_core.devices.DeviceRegistry`` as the sole source of truth rather
than introducing a second, event-volume-based counting mechanism that
would need reconciling against M3's outbox. "Certified" means a device
whose status is ``ACTIVE`` as of the period being metered -- a
``REGISTERED`` device has not yet gone live, and a ``SUSPENDED`` or
``DECOMMISSIONED`` device is not in billable service.

``meter_tenant_period`` is gated on ``billing.flags.MonetizationFlags``:
when monetization is disabled for a tenant, it returns ``None`` and does
nothing else -- no ``UsageRecord`` is constructed, matching the
disabled-mode contract that no billing side effect occurs.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from billing.flags import MonetizationFlags
from domain_core.devices import DeviceRegistry, DeviceStatus

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_TIMESTAMP_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")


class InvalidPeriodError(ValueError):
    pass


@dataclass(frozen=True)
class UsageRecord:
    tenant_id: str
    period_start: str
    period_end: str
    certified_device_count: int

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.tenant_id):
            raise InvalidPeriodError("Invalid tenant_id")
        if not _TIMESTAMP_PATTERN.fullmatch(self.period_start):
            raise InvalidPeriodError("period_start must be an ISO-8601 UTC timestamp")
        if not _TIMESTAMP_PATTERN.fullmatch(self.period_end):
            raise InvalidPeriodError("period_end must be an ISO-8601 UTC timestamp")
        if self.period_end <= self.period_start:
            raise InvalidPeriodError("period_end must be after period_start")
        if self.certified_device_count < 0:
            raise InvalidPeriodError("certified_device_count cannot be negative")


def certified_device_count(registry: DeviceRegistry, tenant_id: str) -> int:
    return sum(1 for device in registry.devices_for_tenant(tenant_id) if device.status == DeviceStatus.ACTIVE)


def meter_tenant_period(
    *,
    flags: MonetizationFlags,
    registry: DeviceRegistry,
    tenant_id: str,
    period_start: str,
    period_end: str,
) -> UsageRecord | None:
    """Returns the tenant's certified usage for the period, or ``None``
    -- without touching ``registry`` at all -- if monetization is
    disabled for this tenant."""
    if not flags.is_monetization_enabled(tenant_id):
        return None
    count = certified_device_count(registry, tenant_id)
    return UsageRecord(
        tenant_id=tenant_id, period_start=period_start, period_end=period_end, certified_device_count=count,
    )
