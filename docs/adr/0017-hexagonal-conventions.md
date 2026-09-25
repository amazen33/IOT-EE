# ADR 0017 -- Hexagonal conventions: ports, adapters, dual transport, per-adapter vendor bans

Status: accepted -- encodes decisions ratified by the repository owner
on 2026-09-24. Numbered at merge per
`docs/adr/README.md`. Makes ADR 0011 Decision 3's "must stay swappable"
intent concrete per service, within ADR 0013's autonomy rule.

Related ADRs in this set: `0015-observability-authority.md`,
`0016-tenancy-and-identity.md`,
`0018-events-and-metering.md`,
`0019-streaming-and-rag.md`.

## Context

`CLAUDE.md` already states hexagonal principles (pure domain core,
database independence, protocol independence). The only Java service,
`services/identity`, has five packages -- `domain/`, `core/`, `rbac/`,
`correlation/`, `web/` -- with no explicit port interfaces: the
controller instantiates its handler with `new`, and the handler takes
raw strings. ADR 0011 Decision 3 originally recorded swappability as
intent, not an enforced guarantee; it now points to the enforcement
below and to this ADR. Maven Enforcer and ArchUnit now enforce
autonomy (ADR 0013 Decision 5). `ArchRules.noClassesOutsideSubpackageDependOnPackages`
exists in `architecture/` for per-adapter vendor bans.

## Decision

1. **Layers and dependency direction.** Each service is layered
   domain → application → adapters, read inner to outer. Compile-time
   dependencies point inward only: adapters depend on application,
   application depends on domain; never the reverse.
2. **Package convention** (per service, under `com.iotee.platform.<service>`):

   | Package | Contains | May depend on |
   | --- | --- | --- |
   | `domain` | entities, value objects, invariants, domain exceptions | JDK only |
   | `application` | commands, queries, handlers (use cases) | `domain`, `port.*` |
   | `port.in` | inbound port interfaces + command/query records | `domain` |
   | `port.out` | outbound port interfaces (persistence, events, identity, payment) | `domain` |
   | `adapter.in.rest` | Spring MVC controllers, DTOs, error mapping | `port.in` |
   | `adapter.in.grpc` | gRPC service implementations | `port.in` |
   | `adapter.out.<concern>` | implementations of `port.out` | `port.out`, vendor SDK for its concern |
| `config` (and the Spring Boot main class) | composition root: wires adapters to ports | everything in this service |

   Current `services/identity` maps as: `core/` → `application` +
   `port.in`; `web/` → `adapter.in.rest`; `rbac/` stays framework-free
   in the domain/application ring; `correlation/`'s framework-free
   context stays inside the ring and its Spring interceptor moves to
   `adapter.in.rest`. The move itself is C1 work. Because `application`
   is framework-free, Spring bean wiring lives only in `config`, never as
   annotations on application classes.
3. **Dual transport through shared inbound ports.** REST and gRPC are
   both driving adapters over the **same** `port.in` interfaces. An
   adapter translates its wire format into the same command or query
   record and calls the same port; it holds no business logic, and the
   two adapters never depend on each other.
4. **Per-adapter vendor ban lists, activated.** Each service declares,
   with `ArchRules.noClassesOutsideSubpackageDependOnPackages`, that a
   vendor SDK may be used only inside its own adapter package. At
   minimum:
   - Kafka clients (`org.apache.kafka..`) only in `adapter.out.messaging`;
   - persistence providers (`org.hibernate..`, `org.eclipse.persistence..`,
     `org.jooq..`) only in `adapter.out.persistence`;
   - `org.keycloak..` only in the identity adapter
     (`0016-tenancy-and-identity.md`);
   - `com.stripe..` only in the payment adapter
     (`0018-events-and-metering.md`);
   - `io.grpc..` only in `adapter.in.grpc` (and generated stubs);
   - `org.springframework.web..` only in `adapter.in.rest`.
   `domain`, `application`, and `port.*` keep the existing framework
   denylist (`org.springframework..`, `jakarta.servlet..`, `jakarta.ws.rs..`).
5. **Database: Postgres adapter only; Oracle is optionality.** Persistence
   is reached only through `port.out` interfaces. Only a PostgreSQL
   adapter is built. An Oracle adapter is not a current requirement; the
   port is kept clean enough that one *could* be written if a customer
   pays for it, and no migration is duplicated for Oracle now.

## Consequences

- Every service gains explicit port interfaces and constructor injection;
  `new`-wiring of handlers inside controllers ends.
- Each vendor ban in item 4 is vacuous until its adapter exists. Like
  the existing vacuous rules, each needs a negative fixture proving it
  fires, and a vacuity test that fails when the adapter appears.
- "Postgres only" means PostgreSQL-specific features (RLS per
  `0016-tenancy-and-identity.md`, pgvector per
  `0019-streaming-and-rag.md`) are used freely. A future Oracle
  adapter would need its own equivalents; that cost belongs to that
  future decision.
- ADR 0013's duplication rule applies: each service owns its own ports
  and adapters; none are shared as runtime JARs.
- Generated gRPC stubs come from `contracts/` per service (ADR 0013
  Decision 9), the same idiom as the event envelope.

## Alternatives rejected

- **Transport-specific application services** (separate logic per REST
  and gRPC) -- rejected: defeats transport swap.
- **Shared ports or adapters library across services** -- rejected by
  ADR 0013.
- **Building an Oracle adapter now** -- rejected by ratification:
  doubles migration effort for a non-requirement.

## Cross-references

ADR 0011 Decisions 1, 3, 8 and Risk 1; ADR 0012 Decisions 4, 6; ADR
0013 Decisions 1, 2, 5, 6, 8, 9; `0016-tenancy-and-identity.md`
(identity port and ban); `0018-events-and-metering.md`
(messaging and payment ports); `0015-observability-authority.md`
(propagation belongs in adapters); `0019-streaming-and-rag.md`
(pgvector as a Postgres adapter concern).

