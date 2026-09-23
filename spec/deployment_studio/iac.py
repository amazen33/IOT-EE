"""deployment_studio.iac: structural validation of an IaC plan document.

Decision (docs/adr/0009-m8-deployment-studio.md): this milestone is
plan/validate-only. ``validate_iac_plan_document`` checks shape only --
required top-level sections, at least one well-formed resource entry --
and refuses outright any document that declares ``apply: true``, which
would be a request to execute rather than plan. No terraform, ansible,
or cloud-provider binding of any kind exists here; a real IaC engine
invocation is blocked, see docs/deployment-studio.md.
"""

from __future__ import annotations

_REQUIRED_TOP_LEVEL_KEYS = ("resources", "variables")


class IacPlanValidationError(ValueError):
    pass


def validate_iac_plan_document(document: dict) -> None:
    if not isinstance(document, dict):
        raise IacPlanValidationError("IaC plan document must be a dict")
    if document.get("apply") is True:
        raise IacPlanValidationError(
            "This milestone is plan/validate-only; a document must never declare apply=true"
        )
    missing = [key for key in _REQUIRED_TOP_LEVEL_KEYS if key not in document]
    if missing:
        raise IacPlanValidationError(f"IaC plan document missing required key(s): {missing}")
    resources = document["resources"]
    if not isinstance(resources, list) or not resources:
        raise IacPlanValidationError("IaC plan document must declare at least one resource")
    for resource in resources:
        if not isinstance(resource, dict) or not resource.get("type") or not resource.get("name"):
            raise IacPlanValidationError("Each resource must be a dict with non-empty 'type' and 'name'")
    if not isinstance(document["variables"], dict):
        raise IacPlanValidationError("'variables' must be a dict")
