# Claude Enterprise development contract

All changes require PR, CI, and human review; an agent must not approve its own work.

Prompt frame: state the milestone, bounded context, verified inputs, acceptance criteria, security constraints, intended files, relevant tests, and non-goals. Distinguish assumptions from observations. Inspect before editing and preserve unrelated work.

Never include raw PII, credentials, keys, production dumps, or vault secret values in prompts, Git, app databases, events, logs, or audit evidence. Use synthetic inputs. Enter source secrets once directly into the scaffold-configured vault; persist only references. Never automatically import secrets from legacy exports. No unrestricted cloud commands or cloud admin credentials in browser flows.

Preserve ThingsBoard rules through authorized export, inventory, shadow comparison, and approval. Screenshots describe visible UI only, never hidden formulas or rules. Enforce tenant authorization, versioned contracts, idempotent consumers, transactional outbox, and redacted evidence. Never claim atomicity across ThingsBoard and Kafka.

Milestones: M0 foundation; M1 Migration Studio; M2 domain core; M3 MQTT/PostgreSQL ingestion, Kafka, TB shadow parity; M4 APISIX/SSR/WebSockets/reports; M5 firmware; M6 immutable evidence/resilience/DR; M7 optional monetization; M8 Deployment Studio/multi-environment.

After every stage run its complete relevant gate and the full regression gate. A stage requires code, tests, docs, deployment artifacts, and passing gates; document unavailable external verification as blocked, not passed. Current M0 gate: `python scripts/check.py`. See `docs/test-plan.md` for future gates. M0 must not provision infrastructure or import legacy data.
