"""device-connectivity bounded context: opaque, tenant-scoped device
identity and connectivity/registration state.

Decision (docs/adr/0003-m2-domain-core.md): device_id is an opaque UUID,
meaningless outside its tenant; any human-friendly label is a separate,
mutable, non-identifying field. This avoids cross-tenant collisions and
keeps hierarchy/location information out of the identifier itself.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import re
import uuid

_TENANT_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,64}")
_UUID_PATTERN = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
)


class DeviceStatus(str, Enum):
    REGISTERED = "registered"
    ACTIVE = "active"
    SUSPENDED = "suspended"
    DECOMMISSIONED = "decommissioned"


@dataclass(frozen=True)
class DeviceId:
    """Opaque, tenant-scoped device identifier. Equality/uniqueness is
    only meaningful within the same tenant_id -- this module never
    compares device ids across tenants."""

    tenant_id: str
    value: str

    def __post_init__(self) -> None:
        if not _TENANT_ID_PATTERN.fullmatch(self.tenant_id):
            raise ValueError("Invalid tenant_id")
        if not _UUID_PATTERN.fullmatch(self.value):
            raise ValueError("device id value must be a UUID")

    @classmethod
    def generate(cls, tenant_id: str) -> "DeviceId":
        return cls(tenant_id=tenant_id, value=str(uuid.uuid4()))


@dataclass(frozen=True)
class Device:
    device_id: DeviceId
    tank_id: str
    label: str
    status: DeviceStatus = DeviceStatus.REGISTERED

    def __post_init__(self) -> None:
        if not isinstance(self.device_id, DeviceId):
            raise ValueError("device_id must be a DeviceId")
        if not self.tank_id:
            raise ValueError("tank_id is required")
        if not isinstance(self.status, DeviceStatus):
            raise ValueError("Invalid status")


class DeviceRegistry:
    """Tenant-scoped device registry. A caller must always supply the
    tenant it believes it is operating in; lookups never cross tenants
    even by accident, because the key is the (tenant_id, uuid) pair
    carried inside DeviceId itself."""

    def __init__(self) -> None:
        self._devices: dict[tuple[str, str], Device] = {}

    def register(self, device: Device) -> Device:
        key = (device.device_id.tenant_id, device.device_id.value)
        if key in self._devices:
            raise ValueError("Device already registered")
        self._devices[key] = device
        return device

    def get(self, tenant_id: str, device_id_value: str) -> Device:
        key = (tenant_id, device_id_value)
        if key not in self._devices:
            raise KeyError("No such device for this tenant")
        return self._devices[key]

    def devices_for_tenant(self, tenant_id: str) -> list[Device]:
        return [device for (tid, _), device in self._devices.items() if tid == tenant_id]
