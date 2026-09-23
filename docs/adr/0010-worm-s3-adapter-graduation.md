# ADR 0010 -- Graduation: a real (test-target) S3 WORM adapter

Status: accepted for planning, 2026-09-22. Not a new numbered milestone
in `CLAUDE.md`'s M0-M8 list -- this is a graduation of one item M6
explicitly left blocked ("a real WORM/object-lock backend, and its
provider/region," `docs/evidence.md`), scoped per the repository owner's
explicit constraints (read-only-in-spirit and reversible: no mutation
beyond a disposable test target, no money, no credentials-in-core; if
the backend can't be kept outside the inert core, that itself is the
signal to record as blocked rather than force through).

## Context

M8 merged (`v0.8.0`) with all of M0-M8 having an initial contract-gated
slice. The repository owner asked to graduate exactly one already-
blocked real-backend item to a real (but non-production, test-target)
implementation, choosing between GitOps read and a WORM test-bucket
store. **WORM test-bucket store** was chosen.

Constraints supplied by the repository owner:
- The real backend lives outside the inert core packages, behind the
  existing provider-neutral contract (`evidence.worm.WormStore`), so
  `scripts/check.py`'s isolation gates stay intact.
- No mutation beyond what a disposable test bucket implies, no money, no
  credentials handled in code.
- If the backend can't live outside core without importing forbidden
  modules, record that as a blocker instead of forcing it through.
- Real mutation (apply, payment capture, failover) stays out of scope --
  not decided here.

A real S3-compatible client needs a real SDK; the repository's own
dependency policy (`docs/adr/0005-m4-gateway-reporting.md`, decision 5)
restricts `requirements.txt` to pure-data/zero-risk packages, a policy
every milestone through M8 preserved (the only entry is `tzdata`). The
repository owner was asked how to resolve this and gave an explicit,
scoped answer (see Decision 2).

## Decisions

1. **New top-level package: `adapters/worm_s3/`.** Not a subpackage of
   `evidence/` -- a real backend implementation is deliberately kept
   outside every inert core package, including the one whose contract it
   implements, so a core package's own isolation-scan directory never
   contains real network/SDK code. `adapters/worm_s3.S3WormStore`
   implements `evidence.worm.WormStore`; it depends on `evidence`
   (the contract it fulfils) but not on `billing`, `firmware`, `gateway`,
   or `deployment_studio`. No inert core package may import `adapters`
   in return -- a real adapter is wired in only by a composition root
   outside all of them, mirroring the direction of every other
   provider-neutral contract in this codebase.
2. **boto3 as an optional extra, not a core dependency.** The repository
   owner rejected both "violate ADR 0005 silently" and "hand-roll AWS
   SigV4 signing in the standard library" (a security-sensitive protocol
   not worth reinventing). The resolution: `boto3` is added to a new,
   separately reviewed, pinned file, `requirements-adapters-s3.txt` --
   `requirements.txt` (the core dependency set ADR 0005 governs) is
   untouched. `adapters/worm_s3/store.py` imports `boto3` lazily, inside
   a function body, never at module scope, so importing the package
   itself never requires the extra to be installed; constructing an
   `S3WormStore` without an injected client and without the extra
   installed raises `AdapterNotInstalledError`, never a bare
   `ImportError`. This amends ADR 0005's scope rather than silently
   violating it: **core (`requirements.txt`) stays pure-data/zero-risk
   only; an adapter package may declare its own optional-extra
   dependency file**, confined and mechanically enforced as described
   below.
3. **Credentials are never read by this project's code.** Connection
   configuration (`WORM_S3_BUCKET`, `WORM_S3_ENDPOINT_URL`,
   `WORM_S3_REGION`) is read from the environment by
   `worm_store_from_env`; actual credentials resolve through boto3's own
   standard credential chain (environment variables, a shared
   credentials file, or an IAM role) and are never seen, stored, or
   forwarded by this project's code -- the same "enter secrets once,
   persist only references" discipline the enterprise contract requires
   elsewhere, applied here by simply never being the thing that touches
   the secret value.
4. **Object keys are record-id-only** (`<record_id>.json`), not
   tenant-prefixed, because `evidence.worm.WormStore`'s ABC methods
   (`get`/`set_legal_hold`/`expire`) take only a `record_id` -- no
   `tenant_id`. `list_for_tenant` lists the bucket and filters
   client-side by the `tenant_id` recorded in each object's body: a
   real, working implementation at test-bucket scale, explicitly not a
   production-scale, indexed one (see `docs/adapters-worm-s3.md`'s
   non-goals).
5. **Legal hold and retention are real S3 Object Lock state**, applied
   in `GOVERNANCE` mode (never `COMPLIANCE`, and never with a bypass
   header) -- consistent with M6's already-documented "no production
   retention claims" limitation. `get` reads the object's actual
   Object Lock legal-hold status back from S3 on every call, rather than
   trusting a value this code could get out of sync with the backend.
6. **Isolation and confinement are mechanically enforced**, not just
   documented, by five new `scripts/check.py` checks:
   `_check_adapters_isolation` (no core package imports `adapters`;
   `adapters.worm_s3` imports none of `billing`/`firmware`/`gateway`/
   `deployment_studio`), `_check_import_allowlist` (an allowlist, the
   inverse of the usual denylist, since this package legitimately needs
   real I/O), `_check_boto3_imported_lazily` (boto3 never at module
   scope), `_check_boto3_confined_to_adapters` (boto3/botocore appear
   nowhere else in the repository), and
   `_check_requirements_txt_has_no_boto3`.

## Consequences

- One of M6's explicitly blocked items -- "a real WORM/object-lock
  backend, and its provider/region" -- is now implemented for real
  against a test target, while `evidence/` itself remains exactly as
  inert as it was before this change (unmodified, still covered by
  `_check_no_forbidden_imports`).
- The project gains its second dependency file and first optional one;
  `requirements.txt` (the core set) is unchanged and still `tzdata`-only.
- No claim is made that this is a production-ready WORM deployment: no
  bucket/region is provisioned or selected for production, no IAM/
  least-privilege role is designed, and delete semantics stop at
  S3's own delete-marker behavior on a versioned bucket (see
  `docs/adapters-worm-s3.md`'s blocked list).
- Real mutation elsewhere (GitOps apply, payment capture, failover)
  remains entirely out of scope, per the repository owner's explicit
  instruction not to scope it now.

## Non-goals (blocked pending real infrastructure/decisions -- see `docs/adapters-worm-s3.md`)

- Any production bucket/region selection, IAM/least-privilege role
  design, or bucket provisioning (Object Lock must be enabled at bucket
  creation time by whoever provisions the real bucket; this project
  provisions nothing).
- A tenant-indexed, production-scale `list_for_tenant` (this graduation
  lists the whole bucket and filters client-side).
- A production retention/compliance claim (`GOVERNANCE` mode, not
  `COMPLIANCE`; `expire`'s delete is S3's own delete-marker semantics on
  a versioned bucket, not guaranteed physical erasure).
- Any real GitOps read, apply, payment capture, or failover (out of
  scope for this graduation by explicit instruction).
- Any infrastructure provisioning (still prohibited at this stage per
  `CLAUDE.md`).
