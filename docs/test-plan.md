# Stage acceptance and regression gates

Every stage delivers code, tests, docs, deployment artifacts, its complete relevant gate, and the full accumulated regression gate. A failed or unavailable required gate blocks acceptance. PR/CI/human review is mandatory; report actual commands/results and external checks separately.

M0: `python scripts/check.py` runs all current unit tests, fixture validation, inert plan policy checks, and required-artifact checks. CI runs the same command on Linux and Windows with Python 3.11 and 3.14. It never runs root Multipass scripts, connects to legacy systems, or provisions cloud resources. Deployment artifact is a validated non-executable foundation plan; this stage does not include an application deployment.

| Stage | Required additions to the full gate |
| --- | --- |
| M1 | Source registration/authorization, vault write/reference-only persistence, secret redaction, dry-run non-mutation, approval transitions and evidence; provider integration tests in isolated test environment |
| M2 | Tenant boundary/negative authorization tests; device/asset relations, domain invariants, unit/formula reference cases, schema compatibility |
| M3 | MQTT/PostgreSQL/Kafka/TB integration; transactional outbox crash/retry; duplicate/out-of-order/replay; tenant isolation; golden rule shadow parity with approved tolerances; quarantine and recovery |
| M4 | APISIX auth/rate/version tests, SSR/BFF end-to-end, WebSocket subscription authorization/reconnect, accessibility, reconciled certified reports and publication approval |
| M5 | Signature/compatibility rejection, provenance, canary/pause/rollback, interrupted downloads and device verification; representative hardware integration |
| M6 | Append-only/WORM retention and privileged deletion tests, hash verification, fault injection, load/soak and backup restore/DR against approved RPO/RTO |
| M7 | Finalized usage only, duplicate safety, effective-dated pricing, period closure/reversals, billing outage isolation |
| M8 | Plan policy, least-privilege runner, approval audit, IaC validation/plan, GitOps reconciliation, isolated environment deployment and rollback across approved profiles |

Future gates must be implemented before claiming those stages complete. M0 tests do not establish production security, rule parity, broker connectivity, WORM retention, or infrastructure readiness. Dependency scanning/SAST, deployment smoke tests, and performance gates must expand with the chosen stack and services.
