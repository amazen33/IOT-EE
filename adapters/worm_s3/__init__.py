"""adapters.worm_s3: a real (S3-compatible) implementation of
evidence.worm.WormStore.

See docs/adapters-worm-s3.md and
docs/adr/0010-worm-s3-adapter-graduation.md for the full scoping and
rationale. Summary: this package graduates one of M6's explicitly
blocked items -- "a real WORM/object-lock backend, and its provider/
region" (docs/evidence.md) -- to a real, but test-target-only,
S3-compatible backend, kept outside every inert core package and behind
the existing provider-neutral WormStore contract.

boto3 is this package's one allowed real third-party dependency (see
store.py's lazy import), declared as an optional extra in
requirements-adapters-s3.txt -- requirements.txt (the core dependency
set) is unaffected, and scripts/check.py mechanically confirms both that
boto3 appears nowhere else in the repository and that it is never
imported at module scope here (so importing this package without the
extra installed never raises a bare ImportError).
"""

from __future__ import annotations

from adapters.worm_s3.store import AdapterNotInstalledError, S3WormStore, worm_store_from_env

__all__ = ["AdapterNotInstalledError", "S3WormStore", "worm_store_from_env"]
