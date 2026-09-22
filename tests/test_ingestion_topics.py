import unittest

from ingestion.topics import (
    QOS_COMMAND,
    QOS_TELEMETRY,
    command_topic,
    parse_topic,
    telemetry_topic,
)


class TopicConventionTests(unittest.TestCase):
    def test_telemetry_topic_shape(self):
        self.assertEqual(
            telemetry_topic("synthetic-tenant-a", "synthetic-device-001"),
            "tenant/synthetic-tenant-a/device/synthetic-device-001/telemetry",
        )

    def test_command_topic_shape(self):
        self.assertEqual(
            command_topic("synthetic-tenant-a", "synthetic-device-001"),
            "tenant/synthetic-tenant-a/device/synthetic-device-001/command",
        )

    def test_qos_is_at_least_once(self):
        self.assertEqual(QOS_TELEMETRY, 1)
        self.assertEqual(QOS_COMMAND, 1)

    def test_parse_topic_round_trips_telemetry(self):
        topic = telemetry_topic("synthetic-tenant-a", "synthetic-device-001")
        self.assertEqual(parse_topic(topic), ("synthetic-tenant-a", "synthetic-device-001", "telemetry"))

    def test_parse_topic_round_trips_command(self):
        topic = command_topic("synthetic-tenant-a", "synthetic-device-001")
        self.assertEqual(parse_topic(topic), ("synthetic-tenant-a", "synthetic-device-001", "command"))

    def test_telemetry_topic_rejects_invalid_tenant(self):
        with self.assertRaises(ValueError):
            telemetry_topic("bad/tenant", "synthetic-device-001")

    def test_telemetry_topic_rejects_invalid_device(self):
        with self.assertRaises(ValueError):
            telemetry_topic("synthetic-tenant-a", "bad device")

    def test_telemetry_topic_rejects_empty_segment(self):
        with self.assertRaises(ValueError):
            telemetry_topic("", "synthetic-device-001")

    def test_parse_topic_rejects_wrong_segment_count(self):
        with self.assertRaises(ValueError):
            parse_topic("tenant/synthetic-tenant-a/device/synthetic-device-001")

    def test_parse_topic_rejects_wrong_literal_segments(self):
        with self.assertRaises(ValueError):
            parse_topic("foo/synthetic-tenant-a/bar/synthetic-device-001/telemetry")

    def test_parse_topic_rejects_unknown_channel(self):
        with self.assertRaises(ValueError):
            parse_topic("tenant/synthetic-tenant-a/device/synthetic-device-001/firmware")

    def test_parse_topic_rejects_foreign_shape(self):
        with self.assertRaises(ValueError):
            parse_topic("completely/unrelated/topic")


if __name__ == "__main__":
    unittest.main()
