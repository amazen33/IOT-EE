"""adapters: real-backend implementations of this project's
provider-neutral contracts.

Every domain package in this repository defines an abstract,
provider-neutral contract for anything that would otherwise be a real
external dependency (migration_studio.vault.VaultProvider,
ingestion.kafka.KafkaPublisher, gateway.auth.AuthProvider,
firmware.signing.SignatureVerifier, evidence.worm.WormStore,
deployment_studio.gitops's desired/observed comparison). Those packages
stay inert -- no network, process, or third-party-SDK I/O -- and are
mechanically enforced as such by scripts/check.py.

adapters/ is where a *real* implementation of one of those contracts
lives, when one graduates from "blocked, pending a real backend" to
"implemented against a real (test-target) backend." It is intentionally
isolated in both directions:

- No inert core package (domain_core, migration_studio, ingestion,
  gateway, reporting, firmware, evidence, foundation, billing,
  deployment_studio) may import anything under adapters/ -- a real
  adapter is wired in only at a composition root outside all of them,
  never reached into from inside the core.
- An adapter itself may depend on the one bounded context whose contract
  it implements (e.g. adapters.worm_s3 imports evidence.worm and
  evidence.records), but not on unrelated bounded contexts such as
  billing, firmware, gateway, or deployment_studio.

An adapter may also be the one place in this repository allowed a real
third-party SDK dependency (see adapters/worm_s3/store.py's boto3 use),
declared as an *optional extra* the core dependency set
(requirements.txt) never gains. See
docs/adr/0010-worm-s3-adapter-graduation.md and
docs/adapters-worm-s3.md.
"""
