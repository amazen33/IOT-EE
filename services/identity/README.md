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

## Package structure: `core/` vs `web/` vs `rbac/` vs `correlation/`

Unchanged from the first commit's REST-independence split, extended to
the relocated code:

- `core.TenantPermissionsHandler` -- plain Java class, plain method
  (`handle(String rawTenantId, String subjectId)`), plain return type
  (`core.TenantPermissionsResult`). No Spring imports anywhere in `core/`
  (enforced by `IdentityArchitectureRulesTest.identityCoreMustStayFrameworkFree`).
  Tested with a bare JUnit test and no Spring context at all -- see
  `TenantPermissionsHandlerTest`.
- `web.TenantPermissionsController` -- the `@RestController` for
  `GET /tenants/{tenantId}/permissions?subjectId=...`. Its only job is
  translating the HTTP request into a call to
  `TenantPermissionsHandler.handle` and mapping the result onto an HTTP
  response; all real logic lives in `core`. `web.WebMvcConfig` registers
  this service's own `CorrelationIdHandlerInterceptor`. `web.DomainExceptionAdvice`
  maps `domain.TenantIdValidationException` (a validation failure thrown
  by `TenantId.of`) to an HTTP 400 with a stable, non-leaking response
  body -- added after CI caught the exception propagating uncaught as a
  500 (`TenantPermissionsControllerTest.nonSyntheticTenantIdIsRejected`);
  see that class's own Javadoc for why the response body deliberately
  never echoes the exception's message.
- `rbac/` -- this service's own RBAC primitives (see above). Framework-
  free by design, and now mechanically enforced: new rule
  `identityRbacMustStayFrameworkFree` (no `org.springframework..`),
  mirroring the pre-existing `core/` rule, added in this refactor because
  `rbac/` code used to live in a module (`common/`) that had its own
  separate framework-freedom rule; now that the code lives here, the
  guarantee has to live here too.
- `correlation/` -- the merged correlation-id context and Spring
  interceptor/filter (see above). No framework-freedom rule: this
  package deliberately holds both the plain context class and its Spring
  adapter now that there is one consumer, not two modules.
- `envelope/`, `schema/` (test-only) -- round-trip tests against the
  generated Protobuf envelope class and the shared `cfg.schema.json`
  contract, both now read from this service's own generated sources /
  the `contracts/` directory directly rather than from a shared jar.

## Explicit non-goals (this refactor does not do this)

- No behavior change to the walking skeleton's endpoint, RBAC seed data,
  or ABAC stub -- this is a structural refactor against ADR 0013, not a
  feature change.
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
The `core` and `rbac` framework-freedom rules, and the retired-shared-
package tripwire, are real and enforced today.

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
