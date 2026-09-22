"""ingestion bounded context: MQTT topic convention.

Decision (docs/adr/0004-m3-ingestion-outbox-delivery.md): tenant-scoped
topic tree, QoS 1 (at-least-once), matching the outbox's duplicate-
tolerant design (publisher retries can duplicate; consumers dedup by
event_id, never the broker's QoS level). Commands use a mirrored topic
under the same tenant/device prefix.
"""

from __future__ import annotations

import re

QOS_TELEMETRY = 1
QOS_COMMAND = 1

_SEGMENT_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")


def telemetry_topic(tenant_id: str, device_id_value: str) -> str:
    _require_segment(tenant_id, "tenant_id")
    _require_segment(device_id_value, "device_id_value")
    return f"tenant/{tenant_id}/device/{device_id_value}/telemetry"


def command_topic(tenant_id: str, device_id_value: str) -> str:
    _require_segment(tenant_id, "tenant_id")
    _require_segment(device_id_value, "device_id_value")
    return f"tenant/{tenant_id}/device/{device_id_value}/command"


def parse_topic(topic: str) -> tuple[str, str, str]:
    """Returns (tenant_id, device_id_value, channel) for a topic this
    module produced. Raises ValueError for anything else -- ingestion
    must never guess at a malformed or foreign topic shape."""
    parts = topic.split("/")
    if len(parts) != 5 or parts[0] != "tenant" or parts[2] != "device":
        raise ValueError(f"Unrecognized topic shape: {topic!r}")
    _, tenant_id, _, device_id_value, channel = parts
    if channel not in ("telemetry", "command"):
        raise ValueError(f"Unrecognized channel in topic: {topic!r}")
    _require_segment(tenant_id, "tenant_id")
    _require_segment(device_id_value, "device_id_value")
    return tenant_id, device_id_value, channel


def _require_segment(value: str, field_name: str) -> None:
    if not isinstance(value, str) or not _SEGMENT_PATTERN.fullmatch(value):
        raise ValueError(f"Invalid {field_name} for topic segment")
