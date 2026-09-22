"""Strict, minimal M0 contracts with field allowlists and no value echoing."""

from datetime import datetime
import math
import re


def validate_event(event, *, authorized_tenant):
    required = {"schema_version", "event_type", "event_id", "tenant_id", "device_id",
                "occurred_at", "correlation_id", "payload"}
    if not isinstance(event, dict) or set(event) != required:
        raise ValueError("Invalid event fields")
    if type(event["schema_version"]) is not int or event["schema_version"] != 1:
        raise ValueError("Unsupported schema version")
    if event["event_type"] != "TelemetryAccepted":
        raise ValueError("Unsupported event type")
    for key in ("event_id", "tenant_id", "device_id", "correlation_id"):
        if not isinstance(event[key], str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", event[key]):
            raise ValueError("Invalid identifier")
    if not authorized_tenant or event["tenant_id"] != authorized_tenant:
        raise ValueError("Tenant mismatch")
    timestamp = event["occurred_at"]
    if not isinstance(timestamp, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", timestamp):
        raise ValueError("UTC timestamp required")
    try:
        datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError("Invalid UTC timestamp") from None
    payload = event["payload"]
    if not isinstance(payload, dict) or set(payload) != {"level_percent", "temperature_c", "battery_percent"}:
        raise ValueError("Invalid telemetry fields")
    for name, value in payload.items():
        if type(value) not in (int, float) or not math.isfinite(value):
            raise ValueError("Finite numeric measurement required")
        if name.endswith("_percent") and not 0 <= value <= 100:
            raise ValueError("Percentage outside range")
        if name == "temperature_c" and value < -273.15:
            raise ValueError("Temperature below absolute zero")
    return (event["tenant_id"], event["event_id"])


def validate_plan(plan):
    expected = {"schema_version": 1, "stage": "M0", "mode": "offline-validation",
                "provisioning_enabled": False, "legacy_import_enabled": False,
                "resources": [], "secret_refs": []}
    if (not isinstance(plan, dict) or plan != expected
            or any(type(plan[key]) is not type(value) for key, value in expected.items())):
        raise ValueError("M0 requires the exact inert offline plan")
