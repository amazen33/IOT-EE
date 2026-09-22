# SpectroTANK modernization

M0 foundation only. No legacy access, exports, rule parity, infrastructure, or production implementation has been verified. Existing root images and Multipass scripts are unverified reference inputs, preserved unchanged. The scripts provision machines and replace guest hosts files; they are not part of the supported deployment or test path and must not be executed by CI.

## Local gate

Use Python 3.11 or newer; no third-party dependencies or network access are required.

```sh
python scripts/check.py
```

The gate runs the complete current test suite, validates synthetic fixtures and the inert deployment plan, and checks required stage artifacts. Python is M0 tooling only; the application language/framework is undecided.

Read [architecture](docs/architecture.md), [decisions](docs/adr/0001-foundation.md), [milestones and test gates](docs/test-plan.md), and [missing inputs](docs/inputs.md). Developer instructions are in [CLAUDE.md](CLAUDE.md).

No remote, branch protection, hosted CI run, container build, or deployment is implied by local gate success. Configure the repository host and required review checks before collaborative delivery.
