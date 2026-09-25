# `services/identity`

Per ADR 0012 Decision 6 and ADR 0013 (microservice autonomy and
contract-based sharing). This module was refactored in place: same
walking-skeleton scope as its first commit, rebuilt so that it depends on
no shared runtime library. It exists to prove that a fully autonomous
Spring Boot service -- generating its own Protobuf classes from a shared
*contract*, owning its own RBAC and correlation-id code, wiring its own
Spring configuration -- works end to end, not to be a usable identity
service yet.

## What changed in the ADR 0013 refactor

Track B's first attempt built `common/` (RBAC, correlation-id context)
and `adapters/web-spring/` (the correlation-id interceptor's Spring Boot
auto-configuration) as shared runtime Java modules that this service
depended on. ADR 0013 ruled that shape out: services share *contract*
artifacts only (`.proto`, JSON Schema, build-time architecture rules),
never executable Java libraries carrying business logic, domain classes,
Spring components, or helper implementations. Both modules are retired.
Their code was relocated into this service's own packages, not deleted:

- `rbac/` (`AbacContext`, `AbacDecision`, `Permission`, `RbacRegistry`,
  `Role`) -- was `common`'s `com.iotee.platform.common.rbac`, now
  `com.iotee.platform.identity.rbac`. This service's own copy; a future
  `services/device` or any other service that needs the same
  authorization seam authors its own copy in its own package (ADR 0013's
  accepted-duplication model), not a shared dependency on this one.
- `correlation/` (`CorrelationIdConstants`, `CorrelationIdContext`,
  `CorrelationIdHandlerInterceptor`, `CorrelationIdServletFilter`) --
  merged from `common`'s framework-free context class and
  `adapters/web-spring`'s Spring interceptor/filter into one package,
  since ADR 0013 no longer requires (or allows) splitting a single
  concern across a "framework-free module" and a "Spring adapter module"
  pair when there is only one consumer.
- `web/WebMvcConfig.java` -- new. `adapters/web-spring`'s Spring Boot
  auto-configuration used to register `CorrelationIdHandlerInterceptor`
  for every consuming service automatically; each service now wires its
  own interceptor explicitly, in its own `WebMvcConfigurer`.

Protobuf: the shared envelope contract now lives at
`contracts/events/v1/envelope.proto` (repo root, sibling to `spec/`),
source only, no generated jars checked in or shared. This module's
`pom.xml` points `protobuf-maven-plugin` at that path
(`../../contracts/events/v1`) and generates its own
`com.iotee.platform.contracts.events.v1.Envelope` classes into its own
`target/generated-sources/` on every build -- the standard per-service
generation idiom ADR 0013 Decision 9 confirms. `cfg.schema.json` moved
the same way, to `contracts/cfg/cfg.schema.json`; this service's schema
test reads it directly from that path rather than from a classpath
resource, since there is no shared module to package it into a jar for.

Mechanical enforcement, both added in this refactor rather than deferred:

- The root `pom.xml`'s Maven Enforcer `bannedDependencies` rule fails
  the build if any module declares a `com.iotee.platform:*` artifact at
  `compile` or `runtime` scope (`test` scope, e.g. `iotee-architecture`,
  is exempt) -- the platform-wide, dependency-declaration-level
  guarantee that no service can add a shared runtime library back.
- `IdentityArchitectureRulesTest`'s
  `noProductionClassResidesInARetiredSharedPackage` -- the
  source-level autonomy tripwire, catching the retired
  `com.iotee.platform.common..` / `com.iotee.platform.adapters.webspring..`
  package names being reintroduced even without a matching `pom.xml`
  dependency.

## Package structure: hexagonal layout (Track C step C1, ADR 0017)

Step C1 re-laid this module out as ports and adapters (ADR 0017
Decisions 1-4). Rings, inner to outer; dependencies point inward only:

| Package | Contains | May depend on (inside this service) |
| --- | --- | --- |
| `domain` | `Tenant`, `TenantId`, `TenantIdValidationException` | nothing |
| `rbac` | `Role`, `Permission`, `RbacRegistry`, `AbacContext`, `AbacDecision` | nothing |
| `port.in` | `GetTenantPermissionsUseCase`, `GetTenantPermissionsQuery`, `TenantPermissionsView`, `InvalidQueryException` | nothing (JDK types only) |
| `port.out` | `RoleAssignmentRepository` | `rbac` |
| `application` | `GetTenantPermissionsService` (implements the inbound port; formerly `core.TenantPermissionsHandler`) | `domain`, `rbac`, `port.*` |
| `adapter.in.rest` | `TenantPermissionsController`, `TenantPermissionsResponse`, `RestExceptionAdvice` (formerly `web.DomainExceptionAdvice`), `CorrelationIdHandlerInterceptor`, `WebMvcConfig` | `port.in`, `correlation` |
| `adapter.in.grpc` | `TenantPermissionsGrpcService`, `GrpcServerRunner` | `port.in` |
| `adapter.out.persistence` | `InMemoryRoleAssignmentRepository` (synthetic seed; step C4 replaces it with PostgreSQL + RLS) | `port.out`, `rbac` |
| `config` | `IdentityServiceConfig` (composition root), `GrpcServerLifecycle` | everything |
| `correlation` | `CorrelationIdConstants`, `CorrelationIdContext` (framework-free) | nothing |

Key points:

- **One inbound port, two transports.** REST
  (`GET /tenants/{tenantId}/permissions?subjectId=...`) and gRPC
  (`TenantPermissionsService.GetTenantPermissions`, contract in
  `contracts/identity/v1/tenant_permissions.proto`) both build the same
  `GetTenantPermissionsQuery` record and call the same
  `GetTenantPermissionsUseCase`. `TransportQueryEquivalenceTest` sends
  equivalent payloads over both real transports (MockMvc dispatch and
  an in-process gRPC server) and asserts the port receives identical
  query objects and both wires return identical answers.
- **Validation lives in `application`, once.** The adapters pass wire
  values through verbatim. `TenantId.of` failures become
  `port.in.InvalidQueryException` (the domain exception is kept as the
  cause, for logs); REST maps it to HTTP 400, gRPC to
  `INVALID_ARGUMENT`, both with the same fixed client-safe text.
- **One intentional behavior change:** a blank `subjectId` is now
  rejected on both transports. proto3 cannot tell an absent
  `subject_id` from `""`, so without this rule the two transports would
  disagree (empty gRPC subject succeeding, REST rejecting a missing
  parameter).
- **Permissions are returned in ascending order** on both transports, so
  responses are deterministic.
- **Spring stays at the edge.** Nothing inside the hexagon carries a
  Spring annotation; `config.IdentityServiceConfig` wires it with plain
  constructors. The gRPC adapter is plain grpc-java;
  `config.GrpcServerLifecycle` starts it with the Spring context
  (`iotee.identity.grpc.enabled`, `iotee.identity.grpc.port`, default
  `9091`; `0` = ephemeral, used by tests).
- **Enforced mechanically** by `IdentityHexagonalArchitectureRulesTest`:
  each adapter depends only on `port.in` (never on the domain, the
  application layer, `port.out`, driven adapters, `config`, or the
  other adapter); `domain`/`rbac`, `port.*` and `application` never
  depend outward or on a transport/framework library; `io.grpc` only in
  `adapter.in.grpc` and `org.springframework.web` only in
  `adapter.in.rest` (ADR 0017 Decision 4). Every rule has a negative
  fixture proving it fires, plus a test that every protected package
  actually contains production classes (so no rule passes vacuously).
- **Not yet:** correlation-ID propagation over gRPC metadata. ADR 0015
  assigns all four correlation carriers to step C2; the REST
  interceptor is unchanged.
- `envelope/`, `schema/` (test-only) -- unchanged round-trip tests
  against the generated envelope class and `contracts/cfg/cfg.schema.json`.

## Explicit non-goals (this refactor does not do this)

- No behavior change to the walking skeleton's endpoint, RBAC seed data,
  or ABAC stub -- this is a structural refactor against ADR 0013 (and,
  in step C1, ADR 0017), not a feature change. Step C1's only
  intentional exceptions: blank `subjectId` is rejected, and permissions
  are returned sorted (see the package-structure section).
- No `TbNode`/`RuleNode`/`AbstractIntegration` code -- see
  `docs/tb-ce-inventory.md` for why (Professional/Cloud-only feature).
- No other `services/*` business logic, and no cross-reactor "no two
  services share a package" scan -- that needs two or more real services
  to compare against and is future work, flagged in ADR 0013 as such.
- No persistence beyond in-memory (`RbacRegistry`'s assignment map), and
  no persistence-provider dependency.
- No SSO/JWT, no full RBAC/ABAC -- two roles, two permissions, an
  always-permit ABAC stub, unchanged from the first commit.
- No Migration Studio or Deployment Studio code.
- No changes anywhere under `spec/` -- that tree is frozen except for its
  own gate (`spec/scripts/check.py`), per ADR 0012 Decision 7, ADR 0012
  Decision 8, and this module's own directive.

## Sibling-isolation rule: currently vacuous, by design

`IdentityArchitectureRulesTest` checks isolation from five sibling
bounded contexts, but none of them exist as Java packages anywhere in
this repository yet -- they are Python-only, under `spec/billing/`,
`spec/firmware/`, etc. `allFiveSiblingIsolationRulesAreCurrentlyVacuous`
documents this mechanically: it fails, forcing this note to be revisited,
the day any of those five is added as a real Java `services/*` package.
The `application`, `domain` and `rbac` framework-freedom rules, the
hexagonal dependency-direction rules, and the retired-shared-package
tripwire are real and enforced today.

## Verification status

`python spec/scripts/check.py` (the existing Python gate) was run and
still passes 437/437 with `spec/` untouched by this refactor.

`mvn verify` was never run to completion in any environment this code
was *written* in: every environment used to prepare it (including the
one used for this note) blocks `repo.maven.apache.org` (Maven Central)
or lacks `mvn`/`javac` entirely, so verification during authoring has
always been mechanical only -- XML well-formedness, brace/paren
balance, ASCII-only content, and a package-declaration-vs-directory-
path consistency check across every `.java` file in this module and in
`architecture/` (the last of these caught a real, previously-
undetected defect in this module's ArchUnit fixture stubs during the
ADR 0013 refactor -- see git history for `rbac/fixtures/` and
`core/fixtures/`).

`mvn -f pom.xml verify` HAS since been run for real, in CI (GitHub
Actions has normal network access and ships Maven -- this repository's
own build environment was never the constraint, only every environment
this code was authored in). First real run: `architecture` passed
(the ArchUnit rules compile and hold); `services/identity` failed 1 of
66 tests -- `TenantPermissionsControllerTest.nonSyntheticTenantIdIsRejected`,
because `domain.TenantIdValidationException` (then a bare
`IllegalArgumentException`) propagated uncaught through
`DispatcherServlet` as a 500 instead of the 400 the test correctly
expected. Fixed by adding `web.DomainExceptionAdvice` (see above) and
narrowing the thrown type from `IllegalArgumentException` to the new
`TenantIdValidationException`; the test itself was not changed, since
it was asserting the right behavior. This is the module's first
compiled-and-executed verification result, superseding the
mechanical-only checks above as the authoritative signal -- re-run
`mvn -f pom.xml verify` after any further change and trust that result
over a mechanical check whenever the two would disagree.

The fix above (`DomainExceptionAdvice` + `TenantIdValidationException`)
was pushed and re-verified: CI run #84 on `feature/track-b-java-
bootstrap` (triggered by commit `2a98d87`, "ci: re-trigger after
641ec96") passed both jobs green -- `foundation` (Python gate,
437/437) and `java-platform-bootstrap` (`mvn -B -f pom.xml verify`,
`architecture` + `services/identity`, all 66 tests). PR #14 merged
that state to `main` at `2cc6e0a`, tagged `v1.0.0` (I3: this paragraph
is the verification-log entry this file was missing at merge time).

Everything added to `services/identity` and `architecture` SINCE that
merge (the M9 conformance-review follow-up commits on this branch --
B-1's reintroduced vendor-SDK/persistence-provider rules, S1/S2/S6's
scan and package-matcher fixes, S4/S5's correlation trust-boundary
config and dead-filter removal) is, once again, verified only
mechanically in this same authoring environment (no `mvn`, no Maven
Central access) -- not yet by a real `mvn -f pom.xml verify` run. A
fresh CI run against this branch is required before treating any of
those items as closed, exactly as it was before CI run #84 existed.

### Track C step C1 (hexagonal skeleton + gRPC adapter)

Status: **passed in CI.** CI run #103
(https://github.com/amazen33/IOT-EE/actions/runs/36109849592) on commit
`878f568` (branch `feature/c1-hexagonal-skeleton`) passed the workflow
"Platform gates: Python spec + Java platform": `mvn -B -f pom.xml verify`
compiled the gRPC adapter and generated stubs and passed the ArchUnit
hexagonal rules, the REST/gRPC equivalence suite and the wiring tests,
alongside the Python gate. PR #21 merged that state to `main` at `8d95e3c`.
This CI run is the authoritative signal. The authoring-time checks below
predate it and are kept as history.

Maven Central (`repo.maven.apache.org`) was unreachable from every
environment this step was authored in (HTTP 403 from the egress proxy), so
before CI the step was checked this way (2026-09-25):

- **Contracts:** `contracts/identity/v1/tenant_permissions.proto` and
  `contracts/events/v1/envelope.proto` compile with the official protoc
  25.5 binary (= `protobuf.version` 3.25.5) and generate the expected
  message classes.
- **Versions:** grpc-java 1.68.1 was chosen because its own v1.68.1
  README pairs it with protoc 3.25.5 and `annotations-api` 6.0.53; not
  assumed.
- **Framework-free rings compile and behave:** `domain`, `rbac`,
  `port.*`, `application`, `adapter.out.persistence`, `correlation`
  compile with `javac --release 17 -Xlint:all` (one pre-existing
  `serial` lint warning on `TenantIdValidationException`), and a
  plain-Java harness passed 17/17 behavior checks (sorted permissions,
  invalid tenant -> `InvalidQueryException` with the domain cause,
  blank subject rejected, validation before the outbound port, ABAC
  filtering, query-record equality, view immutability).
- **Everything else compiles at the signature level:** all main and test
  sources except the two tests that need generated protobuf / JSON
  Schema classes (`EnvelopeSerializationRoundTripTest`,
  `CfgSchemaValidationTest`, both unchanged) compile against hand-written
  stand-ins of the Spring, gRPC, JUnit, MockMvc, Jackson and ArchUnit
  signatures they use. This catches naming and typing errors in this
  module's own code; it does not prove the real libraries behave as
  assumed.
- **ArchUnit rules evaluated on real bytecode:** every rule in
  `IdentityHexagonalArchitectureRulesTest` and the vendor-SDK rule were
  evaluated with `jdeps` class-level dependencies of the compiled code:
  all hold for production classes, and every negative fixture is caught.
  (The spring-web confinement fixture is annotation-only; `jdeps` does
  not report CLASS-retention annotations, but ArchUnit does -- CI run
  #84 passed an identical annotation-only fixture test.)
- **Hygiene:** every `pom.xml` is well-formed XML; every `.java` file's
  package declaration matches its path; no non-ASCII characters in
  sources, protos, poms or YAML.
- **Python gate:** `python spec/scripts/check.py` -- 437 tests, OK
  (1 skipped, the unchanged live-S3 test); `spec/` untouched.

CI run #103 (above) was the first real compile-and-test run of the Spring
and gRPC adapters, the ArchUnit rules and the equivalence suite; step C1 is
closed. Anything added to this module after `8d95e3c` needs its own CI run
before it is treated as verified.
