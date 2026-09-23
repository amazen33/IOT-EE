"""evidence bounded context: immutable audit records, retention/legal-hold
policy, and replay/reconciliation for disaster recovery (M6, per
CLAUDE.md's milestone list: "immutable evidence/resilience/DR").

Scope this milestone (decisions recorded in docs/adr/0007-m6-evidence-resilience-dr.md):
- ``evidence.records``: the ``EvidenceRecord`` shape, a category enum, and
  structural redaction/hash-integrity checks.
- ``evidence.worm``: a provider-neutral write-once-read-many store
  contract (``WormStore``) plus an in-memory test double. A real WORM
  backend (S3 Object Lock, Azure Immutable Blob, an on-prem appliance) and
  its provider/region are blocked pending M8's deployment-profile
  decisions -- see docs/evidence.md.
- ``evidence.capture``: pure functions that turn already-produced
  domain-core / firmware / migration-studio decisions into
  ``EvidenceRecord`` instances, without introducing any new I/O.
- ``evidence.legal_hold``: an RBAC-gated legal-hold flag that structurally
  blocks retention-driven expiry of a held record.
- ``evidence.dr``: documented, per-tenant RPO/RTO objectives plus a
  replay/reconciliation procedure that rebuilds a read-model from durable
  events and reports drift against the live one. Real cross-region
  failover, backup/restore execution, fault injection, and load/soak
  testing are blocked -- see docs/evidence.md.

No module in this package performs network, process, or filesystem I/O
outside the repository (enforced by scripts/check.py's
_check_no_forbidden_imports, same policy as every prior domain package).
"""
