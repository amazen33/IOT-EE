import unittest

from domain_core.commands import CommandRequest
from domain_core.delivery import (
    ConnectionState,
    DeviceConnectionTracker,
    OfflineCommandQueue,
    QueueFullError,
)
from domain_core.units import UtcTimestamp


def _command(command_id="synthetic-command-001", ttl_seconds=60, issued_at="2026-01-01T00:00:00Z"):
    return CommandRequest(
        command_id=command_id,
        tenant_id="synthetic-tenant-a",
        device_id_value="synthetic-device-001",
        property_name="target_level_percent",
        desired_value=42,
        issued_by="synthetic-operator-001",
        issued_at=UtcTimestamp(issued_at),
        ttl_seconds=ttl_seconds,
    )


class DeviceConnectionTrackerTests(unittest.TestCase):
    def test_starts_unknown_and_offline_by_default(self):
        tracker = DeviceConnectionTracker()
        self.assertFalse(tracker.is_online("synthetic-device-001"))

    def test_connect_marks_online(self):
        tracker = DeviceConnectionTracker()
        tracker.connect("synthetic-device-001", now=UtcTimestamp("2026-01-01T00:00:00Z"))
        self.assertTrue(tracker.is_online("synthetic-device-001"))

    def test_disconnect_marks_offline(self):
        tracker = DeviceConnectionTracker()
        now = UtcTimestamp("2026-01-01T00:00:00Z")
        tracker.connect("synthetic-device-001", now=now)
        tracker.disconnect("synthetic-device-001", now=now)
        self.assertFalse(tracker.is_online("synthetic-device-001"))

    def test_history_is_recorded_in_order_per_device(self):
        tracker = DeviceConnectionTracker()
        t1 = UtcTimestamp("2026-01-01T00:00:00Z")
        t2 = UtcTimestamp("2026-01-01T00:05:00Z")
        tracker.connect("synthetic-device-001", now=t1)
        tracker.disconnect("synthetic-device-001", now=t2)

        history = tracker.history_for("synthetic-device-001")
        self.assertEqual([event.state for event in history], [ConnectionState.ONLINE, ConnectionState.OFFLINE])

    def test_history_is_scoped_per_device(self):
        tracker = DeviceConnectionTracker()
        now = UtcTimestamp("2026-01-01T00:00:00Z")
        tracker.connect("synthetic-device-001", now=now)
        tracker.connect("synthetic-device-002", now=now)
        self.assertEqual(len(tracker.history_for("synthetic-device-001")), 1)
        self.assertEqual(len(tracker.history_for("synthetic-device-002")), 1)


class OfflineCommandQueueTests(unittest.TestCase):
    def test_enqueue_increases_queue_depth(self):
        queue = OfflineCommandQueue()
        queue.enqueue("synthetic-device-001", _command())
        self.assertEqual(queue.queue_depth("synthetic-device-001"), 1)

    def test_enqueue_beyond_capacity_raises(self):
        queue = OfflineCommandQueue(max_queue_size=2)
        queue.enqueue("synthetic-device-001", _command("synthetic-command-001"))
        queue.enqueue("synthetic-device-001", _command("synthetic-command-002"))
        with self.assertRaises(QueueFullError):
            queue.enqueue("synthetic-device-001", _command("synthetic-command-003"))

    def test_queue_capacity_is_scoped_per_device(self):
        queue = OfflineCommandQueue(max_queue_size=1)
        queue.enqueue("synthetic-device-001", _command("synthetic-command-001"))
        queue.enqueue("synthetic-device-002", _command("synthetic-command-002"))
        self.assertEqual(queue.queue_depth("synthetic-device-001"), 1)
        self.assertEqual(queue.queue_depth("synthetic-device-002"), 1)

    def test_drain_on_reconnect_returns_commands_in_order(self):
        queue = OfflineCommandQueue()
        queue.enqueue("synthetic-device-001", _command("synthetic-command-001"))
        queue.enqueue("synthetic-device-001", _command("synthetic-command-002"))

        delivered = queue.drain_on_reconnect("synthetic-device-001", now=UtcTimestamp("2026-01-01T00:00:30Z"))

        self.assertEqual([c.command_id for c in delivered], ["synthetic-command-001", "synthetic-command-002"])
        self.assertEqual(queue.queue_depth("synthetic-device-001"), 0)

    def test_drain_on_reconnect_drops_expired_commands(self):
        queue = OfflineCommandQueue()
        queue.enqueue("synthetic-device-001", _command("synthetic-command-001", ttl_seconds=10))

        delivered = queue.drain_on_reconnect("synthetic-device-001", now=UtcTimestamp("2026-01-01T01:00:00Z"))

        self.assertEqual(delivered, [])
        self.assertFalse(queue.has_been_delivered("synthetic-device-001", "synthetic-command-001"))

    def test_drain_on_reconnect_records_delivery_for_replay_prevention(self):
        queue = OfflineCommandQueue()
        queue.enqueue("synthetic-device-001", _command("synthetic-command-001"))
        queue.drain_on_reconnect("synthetic-device-001", now=UtcTimestamp("2026-01-01T00:00:30Z"))
        self.assertTrue(queue.has_been_delivered("synthetic-device-001", "synthetic-command-001"))

    def test_redelivering_the_same_command_id_is_skipped_on_a_second_drain(self):
        # Models a device that reconnects, drops off again before enqueueing
        # anything new, then reconnects again: the same command_id must
        # never be handed to drain_on_reconnect's caller twice.
        queue = OfflineCommandQueue()
        queue.enqueue("synthetic-device-001", _command("synthetic-command-001"))
        first_drain = queue.drain_on_reconnect("synthetic-device-001", now=UtcTimestamp("2026-01-01T00:00:30Z"))
        queue.enqueue("synthetic-device-001", _command("synthetic-command-001"))  # re-enqueued by caller mistake/retry
        second_drain = queue.drain_on_reconnect("synthetic-device-001", now=UtcTimestamp("2026-01-01T00:00:45Z"))

        self.assertEqual(len(first_drain), 1)
        self.assertEqual(second_drain, [])

    def test_empty_queue_drains_to_empty_list(self):
        queue = OfflineCommandQueue()
        self.assertEqual(queue.drain_on_reconnect("synthetic-device-unknown", now=UtcTimestamp("2026-01-01T00:00:00Z")), [])

    def test_has_been_delivered_false_before_any_drain(self):
        queue = OfflineCommandQueue()
        self.assertFalse(queue.has_been_delivered("synthetic-device-001", "synthetic-command-001"))


if __name__ == "__main__":
    unittest.main()
