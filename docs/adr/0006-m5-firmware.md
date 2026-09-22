# ADR 0006 — M5: firmware signing, provenance, and staged rollout/rollback

Status: accepted for planning, 2026-09-22. Implementation is milestone-gated,
same discipline as ADR 0001-0005.

## Context

M5 per `CLAUDE.md`'s milestone list is "firmware." `docs/inputs.md`'s
M5-M6 row listed open inputs: device signing/trust and update
capabilities, firmware provenance process, rollback constraints, RPO/RTO,
evidence retention/legal holds, WORM provider/region and DR ownership.
RPO/RTO and WORM/evidence-retention belong to M6 ("immutable
evidence/resilience/DR" per `CLAUDE.md`'s own milestone list) and are
explicitly not addressed here. The repository owner supplied four
decisions scoping the firmware-specific inputs (recorded below).

## Decisions

1. **Device signing/trust: provider-neutral signature-verification
   contract, real HSM/KMS deferred.** `firmware.signing.SignatureVerifier`
   follows the same shape as every prior milestone's provider abstraction
   (`migration_studio.vault.VaultProvider`, `ingestion.kafka.KafkaPublisher`,
   `gateway.auth.AuthProvider`): an abstract interface plus
   `InMemorySignatureVerifier`, a test double using HMAC-SHA256 over a
   synthetic shared secret — not a real asymmetric signature scheme. A
   real device secure-boot chain or HSM/KMS-issued key never exists here.
2. **Firmware provenance: build metadata + hash + signer identity,
   verified as a unit.** `firmware.provenance.FirmwareArtifact` records
   version, SHA-256 content hash, signer identity, an opaque
   `build_source_ref` (e.g. a commit SHA), and a signature.
   `verify_firmware_provenance` checks the signature against the
   declared signer; shape validation (semver-shaped version, 64-hex-char
   hash, non-empty fields) happens at construction. No real CI/build-system
   integration verifies `build_source_ref`'s authenticity.
3. **Rollout/rollback: staged canary rings with automatic rollback.**
   `firmware.rollout.FirmwareRollout` assigns devices to an ordered
   sequence of rings (canary, then broad, by default), delivers to one
   ring at a time, and only allows advancing to the next ring once every
   device in the current ring has reported healthy. A device reporting
   unhealthy is automatically reverted to its last-known-good version —
   no separate manual rollback command is required for that case. This
   builds directly on M2's `domain_core.commands` — the
   `firmware.rollout_ring` writable property and the
   `device.command.dispatch.firmware` permission already exist there and
   are not redefined; `FirmwareRollout` instead introduces its own
   `firmware.manage` permission (also already in M2's
   `PERMISSION_CATALOG`) for rollout-plan-level operations (assigning
   devices, advancing a ring), distinct from dispatching a command to one
   specific device.
4. **Update delivery: contract-only, same as M4's SSR/WebSocket routes.**
   `firmware.delivery.FIRMWARE_ROUTE_CATALOG` reuses M4's
   `gateway.routes.RouteDefinition` / `build_gateway_route_config` — the
   same validated, APISIX-shaped contract type — for one
   `implemented=False` route (`firmware-artifact-download`). It defines
   its own catalog and its own deployment artifact
   (`deployment/m5-firmware-routes.json`) rather than modifying M4's
   `gateway.routes.ROUTE_CATALOG` or `deployment/m4-gateway-routes.json`,
   which stay exactly as M4 shipped and gated them (verified by
   `tests/test_firmware_delivery.py::test_m4_route_catalog_and_artifact_are_untouched`).
   No real download, flashing, or device-side installer exists anywhere
   in this milestone.

## Consequences

- `FirmwareRollout.__init__` runs `verify_firmware_provenance` before
  accepting an artifact — an unverified or tampered artifact cannot
  structurally enter a rollout, matching the "structural impossibility
  over policy note" discipline `domain_core.commands` already applies to
  cross-tenant dispatch and `gateway.routes` applies to tenant-scoped
  paths.
- A device that fails its post-update health check is never left running
  the failed version and never requires a second, separate rollback
  action — the revert is automatic and immediate as part of
  `report_health`.
- The firmware-artifact-download route contract exists and is tested now,
  so the moment a real distribution mechanism is built, its auth/
  permission requirement is already agreed.
- No claim of real HSM/KMS signing, real build-provenance verification,
  or real firmware delivery is made anywhere in this milestone.

## Non-goals (blocked pending real infrastructure/decisions — see `docs/firmware.md`)

- A real HSM/KMS connection, asymmetric signature scheme, or device
  secure-boot chain.
- Real CI/build-system integration verifying `build_source_ref`'s
  authenticity.
- A real firmware download, flashing, or device-side installer/transport.
- RPO/RTO, evidence retention/legal holds, WORM provider/region, DR
  ownership (M6).
- Any infrastructure provisioning (still prohibited at this stage per
  `CLAUDE.md`).
