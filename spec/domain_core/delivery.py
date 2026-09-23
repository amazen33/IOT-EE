"""alarm-command bounded context, M3 slice: authenticated device delivery
mechanics -- connection tracking, a bounded per-device offline queue, and
reconnect reconciliation with replay prevention
(requirements-addendum.md, M3 row: "authenticated device delivery,
acknowledgment/replay/reconnect behavior").

This still has no real transport: no MQTT client, no broker connection.
It models what happens to already-authorized CommandRequest objects
(domain_core.commands) around a device's online/offline transitions. Real
authenticated delivery over MQTT/mTLS is blocked pending a real broker;
see docs/ingestion.md.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from enum import Enum

from domain_core.commands import CommandRequest, CommandStatus
from domain_core.units import UtcTimestamp

# Bound on how many commands can be queued for one offline device. The
# addendum requires bounded offline queues; a fixed cap is the simplest
# domain-level enforcement of that requirement.
DEFAULT_MAX_QUEUE_SIZE = 50

# How many recently-delivered command ids a device's watermark remembers,
# so a redelivery on the next reconnect is recognized as a duplicate
# rather than replayed to the device again.
DEFAULT_REPLAY_WINDOW = 200


class ConnectionState(str, Enum):
    ONLINE = "online"
    OFFLINE = "offline"


class QueueFullError(RuntimeError):
    pass


@dataclass(frozen=True)
class ConnectionEvent:
    device_id_value: str
    state: ConnectionState
    at: UtcTimestamp


class DeviceConnectionTracker:
    """Tracks the current and historical connection state of devices.
    Pure bookkeeping -- no transport, no real connectivity check."""

    def __init__(self) -> None:
        self._state: dict[str, ConnectionState] = {}
        self._history: list[ConnectionEvent] = []

    def connect(self, device_id_value: str, *, now: UtcTimestamp) -> None:
        self._state[device_id_value] = ConnectionState.ONLINE
        self._history.append(ConnectionEvent(device_id_value, ConnectionState.ONLINE, now))

    def disconnect(self, device_id_value: str, *, now: UtcTimestamp) -> None:
        self._state[device_id_value] = ConnectionState.OFFLINE
        self._history.append(ConnectionEvent(device_id_value, ConnectionState.OFFLINE, now))

    def is_online(self, device_id_value: str) -> bool:
        return self._state.get(device_id_value) == ConnectionState.ONLINE

    def history_for(self, device_id_value: str) -> list[ConnectionEvent]:
        return [event for event in self._history if event.device_id_value == device_id_value]


class OfflineCommandQueue:
    """Bounded, per-device FIFO of commands queued while a device is
    offline, with replay prevention on drain.

    Bounding policy: enqueueing onto a full queue raises QueueFullError
    rather than silently dropping the oldest command -- an operator who
    hits this should see it and decide (retry later, or accept dropping
    something), not have a command silently disappear.
    """

    def __init__(self, *, max_queue_size: int = DEFAULT_MAX_QUEUE_SIZE, replay_window: int = DEFAULT_REPLAY_WINDOW) -> None:
        self._max_queue_size = max_queue_size
        self._queues: dict[str, deque] = {}
        self._delivered: dict[str, deque] = {}
        self._replay_window = replay_window

    def enqueue(self, device_id_value: str, command: CommandRequest) -> None:
        queue = self._queues.setdefault(device_id_value, deque())
        if len(queue) >= self._max_queue_size:
            raise QueueFullError(f"Offline queue for device '{device_id_value}' is full")
        queue.append(command)

    def queue_depth(self, device_id_value: str) -> int:
        return len(self._queues.get(device_id_value, ()))

    def drain_on_reconnect(self, device_id_value: str, *, now: UtcTimestamp) -> list[CommandRequest]:
        """Returns the commands to actually deliver on reconnect: expired
        ones are dropped, and any command_id already recorded as delivered
        to this device is skipped (replay prevention) rather than sent
        again."""
        queue = self._queues.get(device_id_value, deque())
        delivered_ids = self._delivered.setdefault(device_id_value, deque(maxlen=self._replay_window))

        to_deliver: list[CommandRequest] = []
        while queue:
            command = queue.popleft()
            if command.is_expired(now=now):
                continue
            if command.command_id in delivered_ids:
                continue
            to_deliver.append(command)
            delivered_ids.append(command.command_id)

        return to_deliver

    def has_been_delivered(self, device_id_value: str, command_id: str) -> bool:
        return command_id in self._delivered.get(device_id_value, ())
