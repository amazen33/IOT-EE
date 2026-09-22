# Verified baseline and later-stage inputs

Inspection on 2026-09-22 found two shell scripts and five image files; no Git metadata, application, tests, CI, or legacy export was present. Image contents/provenance were not inspected. Existing scripts use Multipass; `create_nodes.sh` launches guests and `link_nodes.sh` enumerates guests and replaces `/etc/hosts`. Neither was executed. No legacy system connection was attempted.

Before M1: approved source owners/access scopes; sanitized export samples; source versions/topology; vault provider, identity integration, reference format and access policy; migration approval roles; data classification and retention requirements. Supply secret values through the eventual vault entry flow, never this repository or prompts.

Before M2–M3: tenant isolation model; device IDs/protocols; canonical units and time semantics; tank geometry/material/formulas; deduplication and ordering rules; throughput/latency/SLO targets; representative authorized rule exports, custom node source/dependencies and licenses; PostgreSQL schemas/time ranges; MQTT topics/QoS; expected golden outcomes and parity tolerances.

Before M4: UI priorities; identity provider; hierarchy/location rules; report definitions and sanitized expected results; timezone and refill/consumption semantics; accessible design requirements; report approvers.

Before M5: device signing/trust and update capabilities; firmware provenance process; rollback constraints (addressed by M5, see docs/firmware.md).

M6 addressed evidence scope, RPO/RTO documentation, and legal-hold enforcement as a provider-neutral contract (see docs/evidence.md and docs/adr/0007-m6-evidence-resilience-dr.md). Still open before a real deployment: WORM provider/region, and DR ownership as an organizational/process assignment.

M7 addressed monetization as a default-off, per-tenant-overridable runtime flag with certified (active-device-count) usage, effective-dated pricing, and immutable period closure with reversal-only correction (see docs/billing.md and docs/adr/0008-m7-monetization.md). Still open: whether monetization is commercially required for any real tenant; commercial/currency/tax requirements; a real payment provider or invoicing system.

M8 addressed selected deployment profiles as an immutable, versioned, default-deny registry (dev/staging/prod environments, closed provider/region enums, prod requires approval) with an RBAC-gated plan lifecycle, approval audit, structural IaC validation, and GitOps drift detection -- all plan/validate-only (see docs/deployment-studio.md and docs/adr/0009-m8-deployment-studio.md). Still open: on-prem/cloud capacity/network constraints; approved IaC runner/GitOps ownership and budgets as organizational decisions; any real apply, provisioning, or GitOps controller connection.

Repository administration still needs a remote host, branch/review protections, CI enablement, application-stack decision, and accountable milestone approvers. Local artifacts cannot configure hosted protections without that input. Dependency/security tooling policy: a first precedent was set in M4 (docs/adr/0005-m4-gateway-reporting.md, decision 5) — a new dependency must be pure-data or otherwise zero-risk, pinned to a minimum version in requirements.txt, and added via reviewed PR; broader tooling (automated scanning, license policy, upgrade cadence) remains an open input.
