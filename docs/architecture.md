# Target architecture (not implemented in M0)

The legacy platform uses ThingsBoard CE for important rules, Mosquitto at multiple layers, and PostgreSQL telemetry before ThingsBoard rule processing. It has no Kafka. These are supplied requirements, not findings from live inspection.

## Boundaries and flow

Bounded contexts: identity/tenant; device/connectivity; asset/tank; telemetry ingestion; rules/automation; alarm/command; reporting; audit/evidence; firmware management; usage metering; optional monetization. Each owns its data and contracts; cross-context database writes are prohibited.

Target flow: MQTT device/edge → ingestion validation and deduplication → transactional data/outbox commit → Kafka canonical versioned events → ThingsBoard compatibility adapter and independent subscribers. Publisher retries can duplicate delivery. All consumers need durable idempotency scoped by tenant, consumer, and event ID. ThingsBoard outcomes become versioned events with causation and correlation IDs. There is no distributed atomic transaction across ThingsBoard and Kafka. Retry, reconciliation, dead-letter handling, and replay policy require explicit design in M3.

ThingsBoard initially preserves valuable rules; it is neither the core source of truth nor the billing engine. Canonical domain stores and certified read models own platform state. Inventory and reconcile legacy behavior before cutover.

## Migration Studio

Support manual/dynamic source registration for Mosquitto live mirrors, PostgreSQL history, ThingsBoard rules/dashboards/devices/assets, and reports. During infrastructure scaffolding configure the vault. Source secrets go directly to that vault once; app DB, Git, events, and audit contain only secret references. Do not automatically import legacy secrets.

Inventory rule chains, custom nodes/scripts, dashboards, devices/assets/relations, formulas, alarms, profiles, integrations, queues, reports, users/roles. Workflow: inventory → dry run → field/formula mapping → preview → shadow comparison → approval → controlled application with immutable evidence. Screenshots may guide visible layout only; they cannot recover rules, formulas, or data.

## Delivery and user experience

Keep existing L3 routing and L4 TCP/UDP load balancing. APISIX is the portable north-south L7 gateway on upstream on-prem Kubernetes, EKS, AKS, and GKE, serving REST/gRPC/WebSocket APIs with authentication, tenancy, rate limits, correlation, and versioning. MQTT remains a broker/L4 path. SSR/BFF serves initial UI. Each browser uses one tenant-authorized WebSocket connection multiplexing permitted UI channels; never expose raw Kafka or device streams to browsers. Reauthorize subscriptions and scope every read model by tenant.

Visible product requirements: executive fleet status, hierarchy/region/site/tank navigation, map/location, level/temperature/alarm/battery, measurement history, refill/consumption, reports, multiple communication types and tank materials.

Reports are a separate migration lane: retain historical exports as evidence, inventory definitions/metadata, rebuild logic on certified event-backed read models, reconcile old/new results, and require approval before publication or scheduling. First pack: executive fleet status, hierarchy/location, tank health, measurement history, refill/consumption, battery/alarm.

## Firmware, evidence, and monetization

Firmware is a separate bounded context initially integrated with TB OTA: signed artifacts, SBOM/provenance, compatibility policy, rollout rings, canary, pause, and rollback. MQTT/TB notifies devices; HTTPS/object storage delivers binaries; devices verify signatures and compatibility. WebSockets show progress only.

Evidence uses an append-only operational ledger plus WORM objects: MinIO Object Lock on-prem, S3 Object Lock on AWS, Azure Blob immutability, GCS Bucket Lock. Store manifests, hashes, retention metadata, and verification results. Kafka is the event backbone, not immutable compliance storage. Retention/legal-hold and privileged deletion controls need verification in M6. No raw PII or secrets in logs, events, or audit.

Usage metering finalizes UsageMetered events. Optional, separately deployable monetization consumes them and owns effective-dated plans, entitlements, rating, invoices, credits, immutable closed periods, and reversals. Never bill directly from raw telemetry or let billing availability block telemetry, alarms, or commands.

## Deployment Studio

Profiles: modular monolith for lab/pilot; distributed single cluster as production default; multi-cluster; edge. Studio generates policy-checked plans. Approved IaC runners use Terraform/OpenTofu to provision infrastructure; Argo CD/GitOps reconciles applications. Public UI must never execute unrestricted cloud commands or expose cloud admin credentials. M0 contains an inert plan only; no provider configuration or apply runner exists.
