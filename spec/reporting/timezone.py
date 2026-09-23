"""reporting bounded context: tenant-local display formatting.

Decision (docs/adr/0005-m4-gateway-reporting.md): storage and computation
stay UTC everywhere (unchanged from M2's domain_core.units decision) --
this module only formats an already-canonical UTC instant for display in
a tenant-configured IANA timezone. It never mutates stored data and is
never used for computation (see reporting.telemetry_summary, which does
all of its math in UTC).
"""

from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


class UnknownTimezoneError(ValueError):
    pass


def to_tenant_local(utc_timestamp: str, iana_tz: str) -> str:
    """Formats a canonical 'YYYY-MM-DDTHH:MM:SSZ' instant as an ISO 8601
    string with the given IANA zone's offset. Display only -- the caller
    must keep using the original UTC string for storage, comparison, or
    any computation."""
    try:
        zone = ZoneInfo(iana_tz)
    except ZoneInfoNotFoundError:
        raise UnknownTimezoneError(f"Unknown IANA timezone: '{iana_tz}'") from None

    try:
        instant = datetime.fromisoformat(utc_timestamp.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError(f"Invalid UTC timestamp: '{utc_timestamp}'") from None

    return instant.astimezone(zone).isoformat(timespec="seconds")
