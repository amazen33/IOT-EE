"""Run from any directory; no network or infrastructure side effects.

Cumulative gate: each stage's checks are ADDED here, never replaced, per
CLAUDE.md ("after every stage run its complete relevant gate and the full
accumulated regression gate"). Sections below are labelled by the stage
that introduced them.
"""

import ast
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from foundation.contracts import validate_event, validate_plan

# Modules that would indicate network/process/filesystem-outside-repo I/O.
# migration_studio must not import any of these: M1 is configuration
# lifecycle and vault *abstraction* only, never a real backend call or a
# legacy system connection.
_FORBIDDEN_IMPORTS_FOR_MIGRATION_STUDIO = {
    "socket", "requests", "urllib", "urllib2", "http", "httpx", "aiohttp",
    "paramiko", "subprocess", "ftplib", "smtplib", "telnetlib", "psycopg2",
    "pymongo", "boto3", "kafka", "paho",
}


def _check_required_artifacts():
    required = [
        # M0
        "README.md", "CLAUDE.md", "docs/architecture.md",
        "docs/adr/0001-foundation.md", "docs/test-plan.md", "docs/inputs.md",
        ".github/workflows/ci.yml", "deployment/m0-plan.json",
        "fixtures/telemetry.synthetic.json", "tests/test_contracts.py",
        # M1 (Migration Studio foundation: vault abstraction + configuration
        # lifecycle; see requirements-addendum.md and docs/test-plan.md's M1 row)
        "docs/adr/0002-migration-studio-foundation.md",
        "docs/migration-studio.md",
        "migration_studio/__init__.py",
        "migration_studio/vault.py",
        "migration_studio/sources.py",
        "migration_studio/evidence.py",
        "fixtures/migration_sources.synthetic.json",
        "tests/test_migration_studio_vault.py",
        "tests/test_migration_studio_sources.py",
        "tests/test_migration_studio_evidence.py",
    ]
    for name in required:
        if not (ROOT / name).is_file() or not (ROOT / name).stat().st_size:
            raise ValueError("Missing required artifact: " + name)


def _check_m0_inert_plan_and_fixtures():
    validate_plan(json.loads((ROOT / "deployment/m0-plan.json").read_text()))
    events = json.loads((ROOT / "fixtures/telemetry.synthetic.json").read_text())
    if not events:
        raise ValueError("Synthetic fixtures required")
    keys = set()
    for event in events:
        key = validate_event(event, authorized_tenant=event["tenant_id"])
        if any(not event[field].startswith("synthetic-") for field in
               ("tenant_id", "device_id", "event_id", "correlation_id")):
            raise ValueError("Synthetic identifiers required")
        if key in keys:
            raise ValueError("Duplicate fixture event")
        keys.add(key)


def _check_m1_migration_studio_policy():
    """Mechanically enforce M1's non-goals rather than relying on
    convention: no network-capable imports anywhere in migration_studio,
    and the synthetic source fixture really is synthetic."""
    for py_file in sorted((ROOT / "migration_studio").glob("*.py")):
        tree = ast.parse(py_file.read_text(), filename=str(py_file))
        for node in ast.walk(tree):
            names = []
            if isinstance(node, ast.Import):
                names = [alias.name.split(".")[0] for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                names = [node.module.split(".")[0]]
            forbidden = set(names) & _FORBIDDEN_IMPORTS_FOR_MIGRATION_STUDIO
            if forbidden:
                raise ValueError(
                    f"{py_file.relative_to(ROOT)} imports forbidden module(s) {sorted(forbidden)}; "
                    "migration_studio must stay free of network/process I/O in M1"
                )

    sources = json.loads((ROOT / "fixtures/migration_sources.synthetic.json").read_text())
    if not sources:
        raise ValueError("Synthetic migration-source fixtures required")
    for entry in sources:
        if not entry.get("source_id", "").startswith("synthetic-"):
            raise ValueError("Migration-source fixture ids must be synthetic")


def main():
    _check_required_artifacts()
    _check_m0_inert_plan_and_fixtures()
    _check_m1_migration_studio_policy()

    suite = unittest.defaultTestLoader.discover(str(ROOT / "tests"))
    if suite.countTestCases() == 0:
        raise ValueError("No tests discovered")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
