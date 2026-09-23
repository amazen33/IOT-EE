"""deployment_studio.runner: a declarative least-privilege runner-policy
contract.

Decision (docs/adr/0009-m8-deployment-studio.md): ``RunnerPolicy``
records the permission scopes a real CI/CD runner would eventually need
for a given profile -- read/write-scoped labels, never a credential or a
real IAM binding. ``allowed_actions`` is structurally forbidden from ever
containing ``"apply"`` or ``"destroy"`` at this milestone: this package
is plan/validate-only, and a runner-policy contract that could declare
"apply" would contradict that at the one place a real pipeline would
read permissions from.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_ID_PATTERN = re.compile(r"[a-zA-Z0-9_-]{1,128}")
_FORBIDDEN_ACTIONS = frozenset({"apply", "destroy"})


class InvalidRunnerPolicyError(ValueError):
    pass


@dataclass(frozen=True)
class RunnerPolicy:
    policy_id: str
    profile_id: str
    allowed_actions: frozenset[str]
    permission_scopes: frozenset[str]

    def __post_init__(self) -> None:
        if not _ID_PATTERN.fullmatch(self.policy_id):
            raise InvalidRunnerPolicyError("Invalid policy_id")
        if not _ID_PATTERN.fullmatch(self.profile_id):
            raise InvalidRunnerPolicyError("Invalid profile_id")
        if not self.allowed_actions:
            raise InvalidRunnerPolicyError("allowed_actions must be non-empty")
        forbidden = set(self.allowed_actions) & _FORBIDDEN_ACTIONS
        if forbidden:
            raise InvalidRunnerPolicyError(
                f"allowed_actions may never include {sorted(forbidden)} at this milestone (plan/validate-only)"
            )
        if not self.permission_scopes:
            raise InvalidRunnerPolicyError("permission_scopes must be non-empty")
