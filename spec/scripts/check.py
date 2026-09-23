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
# migration_studio and domain_core must not import any of these: both are
# domain/configuration logic only, never a real backend call, a legacy
# system connection, or infrastructure provisioning.
_FORBIDDEN_IMPORTS_FOR_DOMAIN_MODULES = {
    "socket", "requests", "urllib", "urllib2", "http", "httpx", "aiohttp",
    "paramiko", "subprocess", "ftplib", "smtplib", "telnetlib", "psycopg2",
    "pymongo", "boto3", "kafka", "paho",
}

# M7: core packages that must never depend on billing -- a billing
# failure must never be able to reach these. "foundation" is included
# deliberately: scripts/check.py itself imports foundation.contracts, and
# many core packages import foundation too, so an unguarded foundation
# would be a transitive backdoor into every core package. Enforced
# mechanically by _check_billing_isolation, not just documented. This is
# the single source of truth for which packages that policy covers --
# don't hardcode a second list elsewhere.
_CORE_PACKAGES_FORBIDDEN_FROM_IMPORTING_BILLING = (
    "domain_core", "migration_studio", "ingestion", "gateway", "reporting", "firmware", "evidence", "foundation",
    "deployment_studio",
)

# M8: deployment_studio must stay a leaf package with respect to the
# other bounded contexts it could plausibly (and wrongly) reach into --
# e.g. firmware rollout state or billing's deployment tier. Enforced
# mechanically by _check_deployment_studio_isolation.
_DEPLOYMENT_STUDIO_FORBIDDEN_IMPORTS = ("billing", "firmware", "evidence", "gateway")

# Graduation (post-M8): adapters/ holds real-backend implementations of
# this project's provider-neutral contracts, starting with
# adapters.worm_s3.S3WormStore -- a real (test-target-only)
# S3-compatible implementation of evidence.worm.WormStore. Framed as a
# graduation of one already-blocked M6 item, not a new numbered
# milestone in CLAUDE.md's list. See
# docs/adr/0010-worm-s3-adapter-graduation.md and
# docs/adapters-worm-s3.md.
_CORE_PACKAGES_FORBIDDEN_FROM_IMPORTING_ADAPTERS = (
    "domain_core", "migration_studio", "ingestion", "gateway", "reporting", "firmware", "evidence", "foundation",
    "billing", "deployment_studio",
)

# adapters.worm_s3 may implement evidence.worm.WormStore for real, but
# must not reach into billing, firmware, gateway, or deployment_studio --
# a real evidence backend has no business touching money, firmware
# rollout, gateway auth, or deployment approvals.
_ADAPTERS_WORM_S3_FORBIDDEN_IMPORTS = ("billing", "firmware", "gateway", "deployment_studio")

# adapters.worm_s3 is the one package in this repo allowed a real
# third-party SDK (boto3, lazily imported -- see
# _check_boto3_imported_lazily) because that is its entire purpose: a
# real S3-compatible backend for evidence.worm.WormStore. In exchange it
# is held to a narrow allowlist instead of _check_no_forbidden_imports's
# denylist -- anything not listed here (a raw socket, an unrelated HTTP
# client, a messaging library, subprocess) is scope creep and must be
# added deliberately, not slip in silently.
_ADAPTERS_WORM_S3_IMPORT_ALLOWLIST = {"__future__", "json", "os", "datetime", "evidence", "boto3", "adapters"}

# boto3/botocore must appear nowhere else in the repo -- core stays
# zero-third-party-dependency (ADR 0005), even though adapters/worm_s3
# itself is allowed the real SDK as an optional extra.
_ALL_NON_ADAPTER_TOP_LEVEL_PACKAGES = (
    "domain_core", "migration_studio", "ingestion", "gateway", "reporting", "firmware", "evidence", "foundation",
    "billing", "deployment_studio", "scripts", "tests",
)


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
        # M2 (domain core: tenancy, RBAC, assets, devices, command domain;
        # see requirements-addendum.md and docs/test-plan.md's M2 row)
        "docs/adr/0003-m2-domain-core.md",
        "docs/domain-core.md",
        "domain_core/__init__.py",
        "domain_core/tenancy.py",
        "domain_core/units.py",
        "domain_core/rbac.py",
        "domain_core/assets.py",
        "domain_core/devices.py",
        "domain_core/commands.py",
        "fixtures/domain_core.synthetic.json",
        "tests/test_domain_core_tenancy.py",
        "tests/test_domain_core_rbac.py",
        "tests/test_domain_core_assets.py",
        "tests/test_domain_core_devices.py",
        "tests/test_domain_core_commands.py",
        "tests/test_domain_core_fixture.py",
        # M3 (ingestion: MQTT topics, transactional outbox, Kafka relay +
        # idempotent consumer, device delivery/offline queue, TB
        # shadow-parity comparison framework; see requirements-addendum.md
        # and docs/test-plan.md's M3 row)
        "docs/adr/0004-m3-ingestion-outbox-delivery.md",
        "docs/ingestion.md",
        "ingestion/__init__.py",
        "ingestion/topics.py",
        "ingestion/outbox.py",
        "ingestion/kafka.py",
        "domain_core/delivery.py",
        "migration_studio/shadow_parity.py",
        "fixtures/shadow_parity.synthetic.json",
        "tests/test_ingestion_topics.py",
        "tests/test_ingestion_outbox.py",
        "tests/test_ingestion_kafka.py",
        "tests/test_domain_core_delivery.py",
        "tests/test_migration_studio_shadow_parity.py",
        # M4 (gateway auth/route contract + reporting read-models; see
        # requirements-addendum.md and docs/test-plan.md's M4 row)
        "docs/adr/0005-m4-gateway-reporting.md",
        "docs/gateway.md",
        "docs/reporting.md",
        "gateway/__init__.py",
        "gateway/auth.py",
        "gateway/routes.py",
        "reporting/__init__.py",
        "reporting/telemetry_summary.py",
        "reporting/timezone.py",
        "reporting/publication.py",
        "deployment/m4-gateway-routes.json",
        "fixtures/reporting_telemetry.synthetic.json",
        "tests/test_gateway_auth.py",
        "tests/test_gateway_routes.py",
        "tests/test_reporting_telemetry_summary.py",
        "tests/test_reporting_timezone.py",
        "tests/test_reporting_publication.py",
        "requirements.txt",
        # M5 (firmware signing/provenance/staged rollout-rollback; see
        # CLAUDE.md's milestone list and docs/inputs.md's M5-M6 row)
        "docs/adr/0006-m5-firmware.md",
        "docs/firmware.md",
        "firmware/__init__.py",
        "firmware/signing.py",
        "firmware/provenance.py",
        "firmware/rollout.py",
        "firmware/delivery.py",
        "deployment/m5-firmware-routes.json",
        "fixtures/firmware_artifacts.synthetic.json",
        "tests/test_firmware_signing.py",
        "tests/test_firmware_provenance.py",
        "tests/test_firmware_rollout.py",
        "tests/test_firmware_delivery.py",
        # M6 (immutable evidence, legal hold, replay/reconciliation for DR;
        # see CLAUDE.md's milestone list and docs/inputs.md's M5-M6 row)
        "docs/adr/0007-m6-evidence-resilience-dr.md",
        "docs/evidence.md",
        "evidence/__init__.py",
        "evidence/records.py",
        "evidence/worm.py",
        "evidence/capture.py",
        "evidence/legal_hold.py",
        "evidence/dr.py",
        "fixtures/evidence_records.synthetic.json",
        "tests/test_evidence_records.py",
        "tests/test_evidence_worm.py",
        "tests/test_evidence_capture.py",
        "tests/test_evidence_legal_hold.py",
        "tests/test_evidence_dr.py",
        # M7 (optional monetization behind a runtime feature flag; see
        # CLAUDE.md's milestone list and docs/adr/0008-m7-monetization.md)
        "docs/adr/0008-m7-monetization.md",
        "docs/billing.md",
        "billing/__init__.py",
        "billing/flags.py",
        "billing/metering.py",
        "billing/pricing.py",
        "billing/closure.py",
        "billing/service.py",
        "fixtures/billing.synthetic.json",
        "tests/test_billing_flags.py",
        "tests/test_billing_metering.py",
        "tests/test_billing_pricing.py",
        "tests/test_billing_closure.py",
        "tests/test_billing_service.py",
        "tests/test_billing_flag_matrix.py",
        "tests/test_billing_fixture.py",
        "tests/test_check_script_gates.py",
        # M8 (Deployment Studio/multi-environment, plan/validate-only;
        # see CLAUDE.md's milestone list and docs/adr/0009-m8-deployment-studio.md)
        "docs/adr/0009-m8-deployment-studio.md",
        "docs/deployment-studio.md",
        "deployment_studio/__init__.py",
        "deployment_studio/profiles.py",
        "deployment_studio/approval.py",
        "deployment_studio/audit.py",
        "deployment_studio/plan.py",
        "deployment_studio/iac.py",
        "deployment_studio/runner.py",
        "deployment_studio/gitops.py",
        "fixtures/deployment_profiles.synthetic.json",
        "tests/test_deployment_studio_profiles.py",
        "tests/test_deployment_studio_approval.py",
        "tests/test_deployment_studio_audit.py",
        "tests/test_deployment_studio_plan.py",
        "tests/test_deployment_studio_iac.py",
        "tests/test_deployment_studio_runner.py",
        "tests/test_deployment_studio_gitops.py",
        "tests/test_deployment_studio_fixture.py",
        # Graduation (post-M8): a real (test-target-only) S3-compatible
        # implementation of evidence.worm.WormStore, kept outside every
        # inert core package -- see
        # docs/adr/0010-worm-s3-adapter-graduation.md and
        # docs/adapters-worm-s3.md.
        "docs/adr/0010-worm-s3-adapter-graduation.md",
        "docs/adapters-worm-s3.md",
        "adapters/__init__.py",
        "adapters/worm_s3/__init__.py",
        "adapters/worm_s3/store.py",
        "requirements-adapters-s3.txt",
        "fixtures/adapters_worm_s3.synthetic.json",
        "tests/test_adapters_worm_s3_contract.py",
        "tests/test_adapters_worm_s3_not_installed.py",
        "tests/test_adapters_worm_s3_live.py",
        "tests/test_adapters_worm_s3_fixture.py",
    ]
    # Since the spec/ relocation (docs/adr/0012-m9-foundation-scope.md
    # Decision 7), ROOT (this script's own parent's parent) is spec/,
    # not the repository root -- but docs/, README.md, CLAUDE.md, and
    # .github/ deliberately stayed at the true repository root (shared
    # between the Python spec and the Java platform, not Python-specific).
    # Entries pointing at any of those resolve against ROOT.parent
    # instead; everything else (the Python packages, scripts/, tests/,
    # fixtures/, deployment/, requirements*.txt) moved under spec/ along
    # with this script and resolves against ROOT unchanged.
    _ROOT_LEVEL_EXACT = {"README.md", "CLAUDE.md", ".github/workflows/ci.yml"}
    for name in required:
        base = ROOT.parent if name.startswith("docs/") or name in _ROOT_LEVEL_EXACT else ROOT
        if not (base / name).is_file() or not (base / name).stat().st_size:
            raise ValueError("Missing required artifact: " + name)


def _check_m0_inert_plan_and_fixtures():
    # foundation/ is M0's own package (validate_event/validate_plan) and
    # is widely depended on by every later domain package, including by
    # this script. It gets the same no-network/process-import scan as
    # every other domain package, not an exemption.
    _check_no_forbidden_imports("foundation")

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


def _check_no_forbidden_imports(package_name):
    """Mechanically enforce a domain package's non-goals rather than
    relying on convention: no network/process-capable imports anywhere in
    it, including in subpackages (rglob, not glob) -- a package that
    starts flat and later grows a subdirectory (e.g. domain_core/policy/)
    must not silently fall out of this scan.

    Transitive imports are not graph-analyzed on purpose: with rglob,
    every file in a real import chain is itself scanned individually
    (A imports B imports socket means B's own file is scanned and
    caught), so the realistic transitive case is already covered without
    building an import-graph analyzer. Do not add one; it is unnecessary
    complexity for what this check needs to catch.

    Known, documented limitation (recorded rather than silently ignored,
    per the same discipline as M6/M7's other blocked items): a dynamic
    import such as importlib.import_module("socket") or
    __import__("socket") is not visible to static AST walking and is not
    caught by this check.
    """
    for py_file in sorted((ROOT / package_name).rglob("*.py")):
        tree = ast.parse(py_file.read_text(), filename=str(py_file))
        for node in ast.walk(tree):
            names = []
            if isinstance(node, ast.Import):
                names = [alias.name.split(".")[0] for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                names = [node.module.split(".")[0]]
            forbidden = set(names) & _FORBIDDEN_IMPORTS_FOR_DOMAIN_MODULES
            if forbidden:
                raise ValueError(
                    f"{py_file.relative_to(ROOT)} imports forbidden module(s) {sorted(forbidden)}; "
                    f"{package_name} must stay free of network/process I/O"
                )


def _check_m1_migration_studio_policy():
    _check_no_forbidden_imports("migration_studio")
    sources = json.loads((ROOT / "fixtures/migration_sources.synthetic.json").read_text())
    if not sources:
        raise ValueError("Synthetic migration-source fixtures required")
    for entry in sources:
        if not entry.get("source_id", "").startswith("synthetic-"):
            raise ValueError("Migration-source fixture ids must be synthetic")


def _check_m2_domain_core_policy():
    _check_no_forbidden_imports("domain_core")
    fixture = json.loads((ROOT / "fixtures/domain_core.synthetic.json").read_text())
    if not fixture.get("tenants"):
        raise ValueError("Synthetic domain-core tenant fixtures required")
    for tenant in fixture["tenants"]:
        if not tenant.get("tenant_id", "").startswith("synthetic-"):
            raise ValueError("Domain-core fixture tenant ids must be synthetic")


def _check_m3_ingestion_policy():
    # ingestion/ is the M3 telemetry-ingestion package: MQTT topic
    # convention, transactional outbox, and the Kafka relay/consumer
    # abstractions (ingestion/kafka.py is this package's own
    # provider-neutral module name -- it must not, and does not, actually
    # import a real "kafka" or "paho" client library). Same static
    # no-network/process-import policy as migration_studio and
    # domain_core, via the shared helper.
    _check_no_forbidden_imports("ingestion")

    fixture = json.loads((ROOT / "fixtures/shadow_parity.synthetic.json").read_text())
    if not fixture.get("pairs"):
        raise ValueError("Synthetic shadow-parity fixture pairs required")
    for pair in fixture["pairs"]:
        for side in ("canonical", "shadow"):
            entry = pair.get(side)
            if entry is not None and not entry.get("event_id", "").startswith("synthetic-"):
                raise ValueError("Shadow-parity fixture event ids must be synthetic")


def _check_m4_gateway_reporting_policy():
    # gateway/ and reporting/ are M4's new packages: an auth/route
    # contract and a CQRS read-model over M3's telemetry. Same
    # no-network/process-import policy as every prior domain package.
    _check_no_forbidden_imports("gateway")
    _check_no_forbidden_imports("reporting")

    routes = json.loads((ROOT / "deployment/m4-gateway-routes.json").read_text())
    if not routes:
        raise ValueError("Gateway route config required")
    for route in routes:
        if not route.get("route_id"):
            raise ValueError("Gateway route entries require a route_id")

    fixture = json.loads((ROOT / "fixtures/reporting_telemetry.synthetic.json").read_text())
    if not fixture.get("readings"):
        raise ValueError("Synthetic reporting-telemetry fixture readings required")
    for reading in fixture["readings"]:
        for field_name in ("event_id", "tenant_id", "device_id"):
            if not reading.get(field_name, "").startswith("synthetic-"):
                raise ValueError("Reporting-telemetry fixture ids must be synthetic")


def _check_m5_firmware_policy():
    # firmware/ is M5's new package: signing verification, provenance,
    # and rollout/rollback policy. Same no-network/process-import policy
    # as every prior domain package.
    _check_no_forbidden_imports("firmware")

    fixture = json.loads((ROOT / "fixtures/firmware_artifacts.synthetic.json").read_text())
    if not fixture.get("artifacts"):
        raise ValueError("Synthetic firmware-artifact fixture entries required")
    for entry in fixture["artifacts"]:
        if not entry.get("artifact_id", "").startswith("synthetic-"):
            raise ValueError("Firmware-artifact fixture ids must be synthetic")

    firmware_routes = json.loads((ROOT / "deployment/m5-firmware-routes.json").read_text())
    if not firmware_routes:
        raise ValueError("Firmware route config required")


def _check_m6_evidence_policy():
    # evidence/ is M6's new package: immutable evidence records, a
    # provider-neutral WORM store contract, legal hold, and
    # replay/reconciliation for DR. Same no-network/process-import policy
    # as every prior domain package.
    _check_no_forbidden_imports("evidence")

    fixture = json.loads((ROOT / "fixtures/evidence_records.synthetic.json").read_text())
    if not fixture.get("records"):
        raise ValueError("Synthetic evidence-record fixture entries required")
    for entry in fixture["records"]:
        if not entry.get("record_id", "").startswith("synthetic-"):
            raise ValueError("Evidence-record fixture ids must be synthetic")
        if not entry.get("tenant_id", "").startswith("synthetic-"):
            raise ValueError("Evidence-record fixture tenant ids must be synthetic")


def _check_forbidden_package_imports(scanned_package, forbidden_names):
    """Shared engine behind _check_billing_isolation and
    _check_deployment_studio_isolation: ast-scans every .py file in
    scanned_package, including subpackages (rglob, not glob -- see
    _check_no_forbidden_imports for why subpackages must be covered, and
    why transitive imports don't need a separate graph analyzer: rglob
    already scans every file in a real chain individually), and raises
    if any of forbidden_names is imported.

    Known, documented limitation: a dynamic import such as
    importlib.import_module(name) is not visible to static AST walking
    and is not caught here -- recorded as a gap, not silently ignored,
    per the same discipline as M6/M7/M8's other blocked items (see
    docs/billing.md and docs/deployment-studio.md).
    """
    forbidden_names = set(forbidden_names)
    for py_file in sorted((ROOT / scanned_package).rglob("*.py")):
        tree = ast.parse(py_file.read_text(), filename=str(py_file))
        for node in ast.walk(tree):
            names = []
            if isinstance(node, ast.Import):
                names = [alias.name.split(".")[0] for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                names = [node.module.split(".")[0]]
            hit = set(names) & forbidden_names
            if hit:
                raise ValueError(
                    f"{py_file.relative_to(ROOT)} imports forbidden module(s) {sorted(hit)}; "
                    f"{scanned_package} must not depend on {sorted(forbidden_names)}"
                )


def _check_billing_isolation():
    """Mechanically enforce M7's billing-outage isolation requirement: no
    other domain package may import billing at all. Billing can only be
    reached through billing.flags.MonetizationFlags.is_monetization_enabled,
    never by a core package reaching into billing directly."""
    for package_name in _CORE_PACKAGES_FORBIDDEN_FROM_IMPORTING_BILLING:
        _check_forbidden_package_imports(package_name, {"billing"})


def _check_deployment_studio_isolation():
    """Mechanically enforce M8's deployment_studio isolation requirement:
    it must not import billing, firmware, evidence, or gateway -- it
    stays a leaf package, reachable by nothing it shouldn't be and
    reaching into nothing it shouldn't need."""
    _check_forbidden_package_imports("deployment_studio", _DEPLOYMENT_STUDIO_FORBIDDEN_IMPORTS)


def _check_import_allowlist(package_name, allowed_names):
    """Inverse of _check_no_forbidden_imports: for a package that
    legitimately needs real I/O (adapters.worm_s3), ban everything not
    explicitly allowed instead of banning a fixed denylist -- scope
    creep (an unrelated network client, a messaging library, a
    subprocess call) doesn't slip in unnoticed."""
    allowed = set(allowed_names)
    for py_file in sorted((ROOT / package_name).rglob("*.py")):
        tree = ast.parse(py_file.read_text(), filename=str(py_file))
        for node in ast.walk(tree):
            names = []
            if isinstance(node, ast.Import):
                names = [alias.name.split(".")[0] for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                names = [node.module.split(".")[0]]
            extra = set(names) - allowed
            if extra:
                raise ValueError(
                    f"{py_file.relative_to(ROOT)} imports {sorted(extra)}, not on {package_name}'s "
                    f"allowlist {sorted(allowed)}"
                )


def _check_boto3_imported_lazily():
    """adapters.worm_s3 must never import boto3 at module scope -- only
    inside a function body -- so importing the module itself (e.g. to
    reference S3WormStore, or to pass in a fake client for tests) never
    raises a bare ImportError when the optional extra isn't installed.
    AdapterNotInstalledError, raised from inside the lazy import, is the
    only surfaced error."""
    for py_file in sorted((ROOT / "adapters" / "worm_s3").rglob("*.py")):
        tree = ast.parse(py_file.read_text(), filename=str(py_file))
        for node in tree.body:
            names = []
            if isinstance(node, ast.Import):
                names = [alias.name.split(".")[0] for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                names = [node.module.split(".")[0]]
            if "boto3" in names:
                raise ValueError(
                    f"{py_file.relative_to(ROOT)} imports boto3 at module scope; "
                    "adapters.worm_s3 must lazy-import it inside a function"
                )


def _check_adapters_isolation():
    """Mechanically enforce the graduation's isolation requirement: no
    inert core package may import adapters (a real backend is wired in
    only at a composition root outside all of them), and adapters.worm_s3
    itself may not reach into billing, firmware, gateway, or
    deployment_studio -- a real evidence backend has no business touching
    money, firmware rollout, gateway auth, or deployment approvals."""
    for package_name in _CORE_PACKAGES_FORBIDDEN_FROM_IMPORTING_ADAPTERS:
        _check_forbidden_package_imports(package_name, {"adapters"})
    _check_forbidden_package_imports("adapters/worm_s3", _ADAPTERS_WORM_S3_FORBIDDEN_IMPORTS)


def _check_boto3_confined_to_adapters():
    """boto3/botocore may appear nowhere in this repo except under
    adapters/ (currently adapters/worm_s3) -- everywhere else keeps the
    project's zero-third-party-dependency-in-core discipline (ADR 0005),
    even though adapters/ itself is allowed the real SDK as an optional
    extra."""
    for package_name in _ALL_NON_ADAPTER_TOP_LEVEL_PACKAGES:
        _check_forbidden_package_imports(package_name, {"boto3", "botocore"})


def _check_requirements_txt_has_no_boto3():
    content = (ROOT / "requirements.txt").read_text().lower()
    if "boto3" in content or "botocore" in content:
        raise ValueError("requirements.txt must stay free of boto3/botocore; see requirements-adapters-s3.txt")


def _check_worm_s3_adapter_graduation_policy():
    # adapters/worm_s3 is this stage's graduation of one already-blocked
    # M6 item: a real (test-target-only) S3-compatible WormStore
    # implementation, living outside every inert core package, behind
    # evidence.worm's existing provider-neutral WormStore contract. It is
    # deliberately exempt from _check_no_forbidden_imports (boto3 is its
    # entire purpose) and held instead to a narrow import allowlist, a
    # lazy-import requirement, and the isolation/confinement checks below.
    _check_import_allowlist("adapters/worm_s3", _ADAPTERS_WORM_S3_IMPORT_ALLOWLIST)
    _check_boto3_imported_lazily()
    _check_adapters_isolation()
    _check_boto3_confined_to_adapters()
    _check_requirements_txt_has_no_boto3()

    fixture = json.loads((ROOT / "fixtures/adapters_worm_s3.synthetic.json").read_text())
    if not fixture.get("records"):
        raise ValueError("Synthetic adapters.worm_s3 fixture entries required")
    for entry in fixture["records"]:
        if not entry.get("record_id", "").startswith("synthetic-"):
            raise ValueError("adapters.worm_s3 fixture record ids must be synthetic")
        if not entry.get("tenant_id", "").startswith("synthetic-"):
            raise ValueError("adapters.worm_s3 fixture tenant ids must be synthetic")


def _check_m7_billing_policy():
    # billing/ is M7's new package: monetization behind a runtime feature
    # flag. Same no-network/process-import policy as every prior domain
    # package, plus the mechanical isolation check above.
    _check_no_forbidden_imports("billing")
    _check_billing_isolation()

    fixture = json.loads((ROOT / "fixtures/billing.synthetic.json").read_text())
    for entry in fixture.get("tenant_overrides", []):
        if not entry.get("tenant_id", "").startswith("synthetic-"):
            raise ValueError("Billing fixture tenant ids must be synthetic")
    if not fixture.get("price_plans"):
        raise ValueError("Synthetic billing price-plan fixture entries required")
    for entry in fixture["price_plans"]:
        if not entry.get("plan_id", "").startswith("synthetic-"):
            raise ValueError("Billing fixture plan ids must be synthetic")


def _check_m8_deployment_studio_policy():
    # deployment_studio/ is M8's new package: multi-environment
    # deployment profiles, plan lifecycle/approval, a runner-policy
    # contract, and GitOps drift detection -- plan/validate-only. Same
    # no-network/process-import policy as every prior domain package,
    # plus the isolation check.
    _check_no_forbidden_imports("deployment_studio")
    _check_deployment_studio_isolation()

    fixture = json.loads((ROOT / "fixtures/deployment_profiles.synthetic.json").read_text())
    if not fixture.get("profiles"):
        raise ValueError("Synthetic deployment-profile fixture entries required")
    for entry in fixture["profiles"]:
        if not entry.get("profile_id", "").startswith("synthetic-"):
            raise ValueError("Deployment-profile fixture ids must be synthetic")
        if not entry.get("tenant_id", "").startswith("synthetic-"):
            raise ValueError("Deployment-profile fixture tenant ids must be synthetic")


def main():
    _check_required_artifacts()
    _check_m0_inert_plan_and_fixtures()
    _check_m1_migration_studio_policy()
    _check_m2_domain_core_policy()
    _check_m3_ingestion_policy()
    _check_m4_gateway_reporting_policy()
    _check_m5_firmware_policy()
    _check_m6_evidence_policy()
    _check_m7_billing_policy()
    _check_m8_deployment_studio_policy()
    _check_worm_s3_adapter_graduation_policy()

    suite = unittest.defaultTestLoader.discover(str(ROOT / "tests"))
    if suite.countTestCases() == 0:
        raise ValueError("No tests discovered")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
