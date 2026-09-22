"""Run from any directory; no network or infrastructure side effects."""

import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from foundation.contracts import validate_event, validate_plan


def main():
    required = ["README.md", "CLAUDE.md", "docs/architecture.md",
                "docs/adr/0001-foundation.md", "docs/test-plan.md", "docs/inputs.md",
                ".github/workflows/ci.yml", "deployment/m0-plan.json",
                "fixtures/telemetry.synthetic.json", "tests/test_contracts.py"]
    for name in required:
        if not (ROOT / name).is_file() or not (ROOT / name).stat().st_size:
            raise ValueError("Missing required M0 artifact: " + name)
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
    suite = unittest.defaultTestLoader.discover(str(ROOT / "tests"))
    if suite.countTestCases() == 0:
        raise ValueError("No tests discovered")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
