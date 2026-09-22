"""firmware-management bounded context, M5 slice: signature verification,
firmware provenance, and staged canary/broad rollout with automatic
rollback. No real HSM/KMS, device secure-boot chain, CI/build-system
integration, or firmware download/flashing transport exists in this
package -- see docs/firmware.md for what is implemented versus blocked.
"""
