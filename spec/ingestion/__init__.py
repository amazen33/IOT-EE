"""telemetry-ingestion bounded context, M3 slice: MQTT topic convention,
the transactional outbox, and the Kafka relay/consumer-idempotency
abstractions. No real MQTT, PostgreSQL, or Kafka connection exists in this
module -- see docs/ingestion.md for what is implemented versus blocked.
"""
