# adapters.worm_s3 -- a real (test-target) S3 WORM adapter

## Stage
A post-M8 graduation, not a new numbered milestone in `CLAUDE.md`'s
M0-M8 list: this promotes one item M6 left explicitly blocked ("a real
WORM/object-lock backend, and its provider/region," `docs/evidence.md`)
to a real, test-target-only implementation. See
`docs/adr/0010-worm-s3-adapter-graduation.md` for full rationale.

## Bounded contexts touched
`adapters` (new). Depends on `evidence.worm` and `evidence.records` (the
contract it implements). No other bounded context may import `adapters`,
and `adapters.worm_s3` may not import `billing`, `firmware`, `gateway`,
or `deployment_studio` (see "Security constraints observed"). `evidence/`
itself is untouched by this change.

## Verified inputs
Decisions supplied by the repository owner for this stage (see ADR 0010
for full detail):
- Graduate the WORM test-bucket store (not GitOps read) -- read-only in
  spirit, reversible, no mutation beyond a disposable test target, no
  money, no credentials-in-core.
- Real backend lives in a new top-level `adapters/` package, outside
  every inert core package, behind the existing `WormStore` contract.
- boto3 as an optional extra (`requirements-adapters-s3.txt`), lazily
  imported, never added to `requirements.txt`; ADR 0005's core
  dependency policy is amended in scope (core stays zero-dependency;
  an adapter may declare its own optional extra), not silently violated.
- Connection configuration via environment variables only
  (`WORM_S3_BUCKET`, `WORM_S3_ENDPOINT_URL`, `WORM_S3_REGION`), pointed
  at a synthetic/test target; credentials are never read by this
  project's code (boto3's own credential chain resolves them).
- Added to `scripts/check.py`'s mandatory gate, but the one test that
  performs real network I/O (`tests/test_adapters_worm_s3_live.py`)
  skips cleanly -- reported as skipped, never as passed -- when
  `WORM_S3_BUCKET` is unset.

## What is implemented
- `adapters/worm_s3/store.py` -- `S3WormStore(WormStore)`: `put`/`get`/
  `list_for_tenant`/`set_legal_hold`/`expire` against a real
  S3-compatible bucket. Legal hold and retention use real S3 Object Lock
  (`GOVERNANCE` mode); `get` reads the object's actual legal-hold status
  back from S3 on every call rather than trusting an in-body value.
  `put`'s duplicate-write rejection is atomic: `IfNoneMatch="*"` on the
  `put_object` call itself is what a concurrent duplicate write is
  actually rejected by (HTTP 412 -> `DuplicateRecordError`); the earlier
  `head_object` read is a fast-path optimization only, not the
  correctness guarantee (see Known Limitations below for backend
  support). `expire` compares parsed timestamps, not raw ISO-8601
  strings, so a `retention_until`/`as_of` pair expressed with different
  (but equivalent) UTC offsets still compares correctly.
  `AdapterNotInstalledError` is raised (never a bare `ImportError`) when
  constructing a store without an injected client and without the
  optional `boto3` extra installed. `worm_store_from_env` builds a store
  from non-secret environment configuration.
- `requirements-adapters-s3.txt` -- a new, separately reviewed, pinned
  optional-extra file (`boto3>=1.34.0`). `requirements.txt` is
  unchanged.
- `fixtures/adapters_worm_s3.synthetic.json` -- documents the three
  connection env-var names and two synthetic evidence records exercised
  end-to-end (against a fake client) in
  `tests/test_adapters_worm_s3_fixture.py`.
- `tests/test_adapters_worm_s3_contract.py` -- full `WormStore` contract
  conformance (put/get roundtrip, write-once duplicate rejection, unknown
  lookup, legal hold overriding expiry regardless of retention, retention
  gating, tenant-scoped listing) against an in-memory `FakeS3Client` --
  no real network I/O, and boto3 need not be installed to run these.
- `tests/test_adapters_worm_s3_not_installed.py` -- proves the module
  imports cleanly without boto3, and that constructing a store without
  an injected client and without boto3 available raises
  `AdapterNotInstalledError`.
- `tests/test_adapters_worm_s3_live.py` -- the one real-backend,
  real-network test in this repository. Skipped (not passed) unless
  `WORM_S3_BUCKET` is set; see the file's docstring for how to run it
  against a disposable test bucket.
- `scripts/check.py` -- five new mechanical checks (see "Security
  constraints observed").

## What is explicitly blocked (not passed, not silently skipped)
- **Any production bucket/region selection or provisioning.** This
  project provisions no infrastructure; a real bucket with Object Lock
  enabled at creation time (Object Lock cannot be added after the fact)
  is a prerequisite the operator supplies, never something this code
  creates.
- **A real IAM/least-privilege role for this adapter.** Credentials
  resolve entirely through boto3's standard chain; no role design,
  policy document, or permission boundary is authored here.
- **A production-scale, tenant-indexed `list_for_tenant`.** This
  graduation lists the whole bucket and filters client-side by the
  `tenant_id` recorded in each object's body -- correct at test-bucket
  scale, not an indexed or paginated production design.
- **A production retention/compliance claim.** Object Lock is applied in
  `GOVERNANCE` mode, never `COMPLIANCE`, and this adapter never requests
  or passes a retention-bypass permission. `expire`'s `delete_object`
  call, on a versioned bucket, creates a delete marker; the prior object
  version remains subject to its own Object Lock retention until a real
  lifecycle rule purges it -- not a guarantee of physical erasure.
- **Any real GitOps read, IaC apply, payment capture, or failover.**
  Entirely out of scope for this graduation, per the repository owner's
  explicit instruction not to scope real mutation now.
- **Any infrastructure provisioning** (still prohibited at this stage
  per `CLAUDE.md`).

These are documented here as blocked, per the contract's requirement to
record unavailable external verification as blocked rather than passed.

## Known Limitations

- **Atomic duplicate-write rejection requires backend support for
  conditional writes.** `put`'s `IfNoneMatch="*"` on `put_object` is the
  actual write-once guarantee under concurrency; AWS S3 itself has
  supported this since August 2024, and S3-compatible targets vary in
  when/whether they added it. If a configured target predates or lacks
  `If-None-Match` support, its behavior on that parameter is
  target-specific (some ignore an unsupported condition silently,
  others error) and has not been verified against every S3-compatible
  backend this adapter might be pointed at. Until a specific target's
  support is confirmed (e.g. by a live-test run against it), treat
  duplicate rejection there as write-once-checked (the `head_object`
  pre-check still runs and still rejects a non-racing duplicate) rather
  than atomic -- adequate at test-bucket scale, where concurrent writers
  racing the same `record_id` are not an expected scenario, but not a
  claim of true concurrency safety against an unconfirmed target.

## Acceptance criteria
| Requirement | How it is met |
| --- | --- |
| Real backend implementation of an existing provider-neutral contract | `adapters/worm_s3/store.py`'s `S3WormStore(WormStore)` |
| Backend kept outside the inert core, isolation gates intact | `_check_adapters_isolation`, `_check_import_allowlist`, `_check_boto3_imported_lazily` in `scripts/check.py`; `evidence/` unmodified |
| No mutation beyond the disposable test target; no money; no credentials-in-core | `worm_store_from_env` reads only non-secret connection config; `expire` requires no legal hold and elapsed retention, exactly as the in-memory double already enforced |
| Mandatory gate, live test skips cleanly without a target | `tests/test_adapters_worm_s3_live.py`'s `@unittest.skipUnless(os.environ.get("WORM_S3_BUCKET"), ...)` |
| Dependency policy amended, not violated | `docs/adr/0010-worm-s3-adapter-graduation.md` decision 2; `requirements-adapters-s3.txt` separate from `requirements.txt`; `_check_requirements_txt_has_no_boto3`, `_check_boto3_confined_to_adapters` |

## Security constraints observed
- `adapters.worm_s3` imports none of `billing`, `firmware`, `gateway`, or
  `deployment_studio` -- mechanically enforced by
  `_check_adapters_isolation`. No inert core package may import
  `adapters` in return -- enforced by the same check, looped over every
  core package.
- `adapters.worm_s3` is exempt from `_check_no_forbidden_imports`'s
  network-import denylist (real I/O is its entire purpose) but held
  instead to `_check_import_allowlist`'s narrow allowlist -- anything not
  explicitly listed (a raw socket, an unrelated HTTP client, a messaging
  library, `subprocess`) is scope creep this check catches.
- `boto3` is never imported at module scope anywhere in
  `adapters/worm_s3` -- enforced by `_check_boto3_imported_lazily` --
  and appears nowhere else in the repository -- enforced by
  `_check_boto3_confined_to_adapters`. `requirements.txt` is
  mechanically confirmed free of `boto3`/`botocore` by
  `_check_requirements_txt_has_no_boto3`.
- Credentials are never read by this project's code; only non-secret
  connection configuration (bucket, endpoint URL, region) passes through
  `worm_store_from_env`.
- `expire` checks legal hold before, and independently of, the
  retention-elapsed check -- identical ordering to
  `evidence.worm.InMemoryWormStore`, now proven against a real backend's
  actual Object Lock state rather than an in-memory flag.

## Intended files
`adapters/{__init__,worm_s3/__init__,worm_s3/store}.py`,
`requirements-adapters-s3.txt`,
`fixtures/adapters_worm_s3.synthetic.json`,
`tests/test_adapters_worm_s3_{contract,not_installed,live,fixture}.py`,
`docs/adr/0010-worm-s3-adapter-graduation.md`, this file, and the five
new checks plus required-artifact entries in `scripts/check.py`.

## Relevant tests
All `tests/test_adapters_worm_s3_*.py`, run by `python scripts/check.py`.
`test_adapters_worm_s3_live.py` requires `WORM_S3_BUCKET` (and the
`requirements-adapters-s3.txt` extra installed) to do anything beyond
skip.

## Non-goals (explicitly out of scope for this change)
- Any production bucket/region selection, provisioning, or IAM role
  design (see blocked list).
- A production-scale, indexed `list_for_tenant`.
- A production retention/compliance claim.
- Any real GitOps read, IaC apply, payment capture, or failover.
- Any infrastructure provisioning.
- Changes to `evidence/`, `billing/`, `firmware/`, `gateway/`, or
  `deployment_studio/` -- this graduation adds a new, isolated package
  and touches nothing else.
