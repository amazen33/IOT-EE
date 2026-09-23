# architecture/

Shared ArchUnit rule definitions, per ADR 0013 Decision 8. This module
is the one exception ADR 0013 makes to "no shared runtime library" --
and it earns that exception only by staying a build-time-only artifact.

## The rule this module exists to satisfy

ADR 0013 permits sharing "architecture rules (build-time)" alongside
`.proto`/OpenAPI/JSON Schema contracts. Everything in this module is a
rule DEFINITION (an `ArchRule` factory method) or a scan-scoping helper
-- nothing here has a runtime effect, nothing here is a domain class,
and nothing here is ever on a running service's classpath.

**Every consumer must declare this module at `<scope>test</scope>`.**
The parent `pom.xml`'s `enforce-no-shared-runtime` Maven Enforcer rule
fails the build if any module declares `iotee-architecture` at
`compile` or `runtime` scope -- see `services/identity/pom.xml` for the
correct declaration.

## What this module does NOT do

- It does not know about any specific service's package names. Every
  factory method in `ArchRules` takes the caller's package roots as
  parameters.
- It does not contain its own architecture tests -- there is no
  business logic here to check boundaries on.
- It does not replace a service's own negative-test fixtures. Each
  service still authors its own fixtures (deliberately-violating
  classes) in its own test tree, and still writes its own test methods
  that call these factories and assert against
  `ImportHelper.importProductionClasses` / `importFixturePackages`.
  This module centralizes the rule TEXT only, not the fixtures that
  prove each rule fires.

## Layout

- `ArchRules.java` -- rule factories: framework/vendor-package
  denylists, sibling-bounded-context isolation, and the ADR 0013
  "autonomy tripwire" (no class may reside in a retired shared package
  name, e.g. the old `common.rbac`/`adapters.webspring` namespaces
  Track B's first attempt used before ADR 0013).
- `ImportHelper.java` -- the production-vs-fixture scan distinction
  every architecture test needs to get right (a production scan must
  exclude test classes via `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`,
  or a rule's own negative-test fixtures get caught by the production
  check itself -- the exact bug Track B's first attempt hit and fixed).
