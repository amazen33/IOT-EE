# Verified baseline and later-stage inputs

Inspection on 2026-09-22 found two shell scripts and five image files; no Git metadata, application, tests, CI, or legacy export was present. Image contents/provenance were not inspected. Existing scripts use Multipass; `create_nodes.sh` launches guests and `link_nodes.sh` enumerates guests and replaces `/etc/hosts`. Neither was executed. No legacy system connection was attempted.

Before M1: approved source owners/access scopes; sanitized export samples; source versions/topology; vault provider, identity integration, reference format and access policy; migration approval roles; data classification and retention requirements. Supply secret values through the eventual vault entry flow, never this repository or prompts.

Before M2–M3: tenant isolation model; device IDs/protocols; canonical units and time semantics; tank geometry/material/formulas; deduplication and ordering rules; throughput/latency/SLO targets; representative authorized rule exports, custom node source/dependencies and licenses; PostgreSQL schemas/time ranges; MQTT topics/QoS; expected golden outcomes and parity tolerances.

Before M4: UI priorities; identity provider; hierarchy/location rules; report definitions and sanitized expected results; timezone and refill/consumption semantics; accessible design requirements; report approvers.

Before M5–M6: device signing/trust and update capabilities; firmware provenance process; rollback constraints; RPO/RTO; evidence retention/legal holds; WORM provider/region and DR ownership.

Before M7–M8: whether monetization is required; certified usage dimensions and closure rules; commercial/currency/tax requirements; selected deployment profiles; on-prem/cloud capacity/network constraints; approved IaC runner/GitOps ownership and budgets.

Repository administration still needs a remote host, branch/review protections, CI enablement, application-stack decision, and accountable milestone approvers. Local artifacts cannot configure hosted protections without that input. Dependency/security tooling policy: a first precedent was set in M4 (docs/adr/0005-m4-gateway-reporting.md, decision 5) — a new dependency must be pure-data or otherwise zero-risk, pinned to a minimum version in requirements.txt, and added via reviewed PR; broader tooling (automated scanning, license policy, upgrade cadence) remains an open input.
