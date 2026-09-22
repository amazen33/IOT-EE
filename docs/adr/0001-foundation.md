# ADR 0001 — Preserve legacy behavior; introduce explicit boundaries

Status: accepted for planning, 2026-09-22. Implementation is milestone-gated.

Context: valuable ThingsBoard CE rules cannot be recreated safely from memory. Legacy PostgreSQL precedes rule processing, and no Kafka exists today.

Decision: retain TB as a compatibility rules component; introduce canonical versioned Kafka events with transactional ingestion outbox and idempotent subscribers. Keep billing optional and downstream of finalized usage. Separate migration, reporting, firmware, and deployment workflows. Use portable APISIX L7 and Kubernetes/GitOps; retain broker/L4 MQTT paths. Use append-only ledger plus provider WORM storage for evidence.

Consequences: migration requires authorized exports, measured rule/report parity, replay/reconciliation, explicit approvals, and operational ownership. At-least-once delivery is expected. Kafka retention alone is insufficient evidence protection. No cross-system exactly-once or atomicity guarantee is claimed.

Rejected: replacing rules from screenshots/memory; making ThingsBoard the billing engine; billing raw telemetry; browser access to raw Kafka; browser cloud-admin execution; treating a lab VM script as production Kubernetes scaffolding.

M0 implementation choice: standard-library Python validates a narrow synthetic event contract and inert deployment plan. This is test scaffolding, not an ingestion service, production security boundary, schema registry, or application-stack decision. Choose runtime, storage topology, versions, and identity/vault providers after missing inputs are resolved.
