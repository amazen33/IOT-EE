# `spec/` — M0–M8 reference implementation

This tree is the Python M0–M8 reference implementation, **not the
platform**. Per [ADR 0011](../docs/adr/0011-platform-architecture-and-language-stack.md)
(Decision 9), the actual platform is Java 17 / Spring Boot / Spring
Cloud, extending ThingsBoard CE as an upgradeable compatibility core;
Python is scoped to the RAG service and standalone adapters going
forward, never the platform's own business logic.

What lives here and why it still matters:

- Every bounded context this project designed and proved out from M0
  through M8 (`foundation/`, `domain_core/`, `migration_studio/`,
  `ingestion/`, `gateway/`, `reporting/`, `firmware/`, `evidence/`,
  `billing/`, `deployment_studio/`), plus the post-M8 graduation
  (`adapters/`, starting with `adapters/worm_s3`).
- `scripts/check.py` — the cumulative regression gate this whole tree
  is held to. Still the mechanically-enforced source of truth for this
  spec's own correctness; it is the direct model for the Java
  platform's own ArchUnit-based gate (see
  [ADR 0012](../docs/adr/0012-m9-foundation-scope.md), Track B), not a
  gate the Java platform itself runs.
- `tests/`, `fixtures/`, `deployment/` — this spec's own test suite,
  synthetic fixtures, and non-executable deployment artifacts.
- `requirements.txt` / `requirements-adapters-s3.txt` — this spec's own
  dependency surface (a deliberately pure-data, near-zero-dependency
  core, plus `adapters/worm_s3`'s one optional real-backend extra).

## Why this moved here

Originally the repository root, this tree relocated to `spec/` as its
own atomic commit (ADR 0012, formerly drafted as the M9 foundation-
scope ADR, Decision 7), sequenced after the WORM-adapter PR (ADR 0010)
merged and before the Java platform's own Track B work began — so that
both this tree's `adapters/` and the Java skeleton's own `adapters/`
directory could exist without a name collision. The move is a `git mv`
relocation only: no file's content changed, and `git log --follow`
traces every moved file's full history across it.

## Running the gate

From the repository root:

```
python spec/scripts/check.py
```

`docs/`, `CLAUDE.md`, `README.md`, and `.github/workflows/` stayed at
the repository root — they describe (or, for CI, drive) the project as
a whole, not this spec alone, and are shared with the Java platform
going forward.

## What this is not

Not a service anyone deploys. Not where new platform features are
built. Not upgraded to track new milestones (M9+ is Java, under a
future `services/`, `common/`, and `adapters/` at the repository root,
per ADR 0011/0012). This tree stays exactly what M0–M8 already proved:
a reference for the bounded contexts, contracts, and mechanical-gate
discipline the Java platform is now expected to carry forward.
