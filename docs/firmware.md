# Firmware — M5

## Milestone
M5, per `CLAUDE.md`'s milestone list: "firmware."

## Bounded contexts touched
firmware-management (new), gateway (extended with a contract-only route,
M4's own catalog untouched).

## Verified inputs
Decisions supplied by the repository owner for this stage (see ADR 0006
for full rationale):
- Device signing/trust: provider-neutral signature-verification contract,
  real HSM/KMS deferred.
- Firmware provenance: build metadata + hash + signer identity, verified
  as a unit.
- Rollout/rollback: staged canary rings with automatic rollback on a
  failed health check.
- Update delivery: contract-only, same discipline as M4's SSR/WebSocket
  routes.

Still open per `docs/inputs.md` and explicitly **not** addressed by this
stage: RPO/RTO, evidence retention/legal holds, WORM provider/region, DR
ownership (all M6 — "immutable evidence/resilience/DR").

## What is implemented
- `firmware/signing.py` — `SignatureVerifier` (abstract, provider-neutral),
  `InMemorySignatureVerifier` (test double: HMAC-SHA256 over a synthetic
  shared secret, with a `sign` test/fixture helper), `UnknownSignerError`.
- `firmware/provenance.py` — `FirmwareArtifact` (validated: semver-shaped
  version, 64-hex-char SHA-256 hash, non-empty signer/build-ref/signature),
  `verify_firmware_provenance`, `ProvenanceError`.
- `firmware/rollout.py` — `RolloutRing` (`CANARY`, `BROAD`),
  `RolloutStatus`, `DeviceFirmwareState`, `FirmwareRollout`
  (`assign_device`, `deliver_to_ring`, `report_health` with automatic
  rollback to last-known-good, `can_advance_ring`, `advance_ring`),
  `FirmwarePermissionError`, `RolloutBlockedError`,
  `DeviceNotInRolloutError`. Gated by M2's existing `firmware.manage`
  permission (rollout-plan operations) — `device.command.dispatch.firmware`
  (M2's per-device command dispatch) is reused, not redefined.
- `firmware/delivery.py` — `FIRMWARE_ROUTE_CATALOG` (one
  `implemented=False` route reusing M4's `gateway.routes.RouteDefinition`),
  `deployment/m5-firmware-routes.json` (its own deployment artifact,
  asserted reproducible by `tests/test_firmware_delivery.py`, which also
  asserts M4's own catalog/artifact are untouched).
- `fixtures/firmware_artifacts.synthetic.json` — two synthetic firmware
  artifacts with precomputed valid HMAC signatures, exercised end-to-end
  in `tests/test_firmware_provenance.py::SyntheticFixtureTests`.

## What is explicitly blocked (not passed, not silently skipped)
- **A real HSM/KMS connection or asymmetric signature scheme.**
  `InMemorySignatureVerifier` is a test double using HMAC-SHA256 over a
  synthetic shared secret; no real key-management service exists.
- **A real device secure-boot chain.** Nothing in this milestone verifies
  anything on an actual device; `SignatureVerifier` verifies an artifact
  record's declared signature, not a device's boot process.
- **Real CI/build-system provenance verification.** `build_source_ref` is
  validated only for presence/shape; its authenticity against a real
  build system is not checked.
- **A real firmware download, flashing, or device-side installer.**
  `firmware-artifact-download` exists as a validated route contract only
  (`implemented=False`); no transport, download, or installer exists.
- **RPO/RTO, evidence retention, WORM, DR ownership.** All explicitly
  deferred to M6.

These are documented here as blocked, per the contract's requirement to
record unavailable external verification as blocked rather than passed.

## Acceptance criteria (traced to firmware-relevant items in docs/inputs.md and docs/requirements-addendum.md)
| Requirement | How it is met |
| --- | --- |
| Signature/compatibility rejection | `tests/test_firmware_provenance.py` — tampered signature, tampered hash, unknown signer all rejected |
| Provenance | `FirmwareArtifact`'s construction-time validation + `verify_firmware_provenance` |
| Canary/pause/rollback | `tests/test_firmware_rollout.py` — ring gating (`can_advance_ring`/`RolloutBlockedError`), automatic rollback on unhealthy report |
| Representative hardware integration | Not applicable — no real device/transport exists this milestone (see blocked list) |

## Security constraints observed
- A `FirmwareRollout` cannot be constructed for an artifact whose
  provenance does not verify — `verify_firmware_provenance` runs in
  `__init__`, so a tampered or unsigned artifact is a structural
  impossibility to roll out, not a policy note.
- Rollout-plan operations (`assign_device`, `deliver_to_ring`,
  `advance_ring`) require the `firmware.manage` permission; `report_health`
  deliberately does not, since a health report is device-originated
  telemetry, not an operator action, and gating it behind operator RBAC
  would be the wrong trust boundary.
- A device that fails its health check is reverted immediately as part of
  `report_health` — there is no window where a known-unhealthy version is
  left running while a separate manual rollback is arranged.
- `firmware.delivery`'s route requires `device.command.dispatch.firmware`,
  the same permission M2's command domain already uses for
  firmware-property writes, so firmware distribution and firmware command
  dispatch share one authorization boundary rather than two.

## Intended files
`firmware/{__init__,signing,provenance,rollout,delivery}.py`,
`deployment/m5-firmware-routes.json`, `fixtures/firmware_artifacts.synthetic.json`,
`tests/test_firmware_{signing,provenance,rollout,delivery}.py`,
`docs/adr/0006-m5-firmware.md`, this file, and the extension to
`scripts/check.py`.

## Relevant tests
All `tests/test_firmware_*.py`, run by `python scripts/check.py`.

## Non-goals (explicitly out of scope for this change)
- Any real HSM/KMS, secure-boot chain, build-system integration, or
  firmware transport (all blocked, see above).
- RPO/RTO, evidence retention/legal holds, WORM, DR (M6).
- Monetization or deployment-profile concerns (M7-M8).
- Any infrastructure provisioning.
