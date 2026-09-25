# contracts/

Shared contract sources for the IOT-EE platform, per ADR 0013
(microservice autonomy and contract-based sharing).

This directory holds **source only**: `.proto` files and JSON Schema
documents. There is no `pom.xml` here and nothing here is ever compiled
or published as a jar from this location. A service that needs one of
these contracts configures its own build to generate code from it (see
`services/identity/pom.xml`'s `protobuf-maven-plugin` configuration for
the reference example) -- the generated classes are that service's own
build artifact, never a shared runtime dependency.

`contracts/` lives at the repository root, as a sibling to `spec/`,
`services/`, and `adapters/`, because it is language-neutral: both this
platform's Java services and any future Python consumer (the RAG
subsystem, per ADR 0011 Decision 1) generate their own code from the
same source here. It does not live under `spec/`, which is scoped
specifically to the Python M0-M8 reference implementation (ADR 0012
Decision 7), a narrower and different thing.

## Layout

- `events/v1/envelope.proto` -- the CloudEvents-shaped platform
  event/command envelope (ADR 0012 Decision 4). Versioned by directory
  (`v1/`); a breaking change to the envelope shape adds `v2/` alongside
  it rather than editing `v1/` in place.
- `identity/v1/tenant_permissions.proto` -- the identity service's
  gRPC contract (`TenantPermissionsService.GetTenantPermissions`), the
  gRPC twin of `GET /tenants/{tenantId}/permissions` (ADR 0017
  Decision 3). Same versioning-by-directory rule as `events/v1/`.
- `cfg/cfg.schema.json` -- the draft `cfg.yaml` JSON Schema every
  service's own configuration is validated against (ADR 0012 Decision
  3).

## What does not belong here

- Generated code of any kind (Java, Python, or otherwise).
- Anything with a method body implementing a decision -- that is
  project-authored runtime logic, which ADR 0013 requires to live in
  each service's own source tree, duplicated where needed, never
  shared from a common location including this one.
