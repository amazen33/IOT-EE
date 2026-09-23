# TB CE inventory (Track A)

Status: Track A deliverable per [ADR 0012](adr/0012-m9-foundation-scope.md)
(Decision 1, Track A). Document only -- no Java, no Python, no code in
this PR. Pins the TB CE version the Java platform extends, inventories
its extension surface at that version, drafts the first
`tb-extensions/manifest.yaml`, and captures one synthetic representative
event.

**Assumptions vs. observations.** Everything under "Extension API
inventory" is an observation: fetched from ThingsBoard's own public
documentation and source (cited inline, version-specific where the
repository structure allows it). Anything about *this* project's legacy
system (Spectro Systems' current TB CE deployment) is stated as an
assumption or open question, since this document has no access to that
system -- see "Legacy version" below and "Gaps and unknowns" at the end.

## 1. Pinned TB CE version

**Target version for the new platform: ThingsBoard CE v4.3.1.5** (released
September 14, 2026), the current stable release as of this document.
[GitHub releases](https://github.com/thingsboard/thingsboard/releases) |
[v4.3.x release notes](https://thingsboard.io/docs/releases/releases-table/v4-3-x/)

Why this version:

- It is the current stable release on the actively-maintained `4.3.x`
  line, not a preview or release candidate.
- The `4.3` line introduced Alarm Rules 2.0, enhanced Calculated
  Fields, and API keys as a JWT alternative -- see the
  [ThingsBoard 4.3 release announcement](https://thingsboard.io/blog/thingsboard-4-3-alarm-rules-2-0-enhanced-calculated-fields-api-keys-and-more/).
  API keys in particular are relevant to this platform's own gateway
  auth story (ADR 0011 Decision 3's APISIX edge).
- ThingsBoard ships frequent patch releases on its current stable line
  (v4.3.1.1 through v4.3.1.5 landed roughly every 4-8 weeks, each
  carrying security CVE fixes -- see the release-notes table above).
  **Version pinning strategy** (also stated in `tb-extensions/manifest.yaml`
  below): pin to the `4.3.1.x` patch line, not a single exact patch
  build, and re-pin to the latest `4.3.1.x` patch as a routine,
  low-friction bump (per ADR 0011 Decision 8's "minimal customization
  set" upgrade philosophy) rather than freezing on v4.3.1.5
  indefinitely. A minor-version bump (4.3 -> 4.4 or later) is a
  deliberate, reviewed decision, not automatic.

**Legacy version: unknown -- not determined by this document.**
Spectro Systems' existing SpectroTANK platform runs an older TB CE
version (per prior conversation context), but this document has no
access to that deployment (its running version, its rule chains, or
its configuration) and does not guess a version number. This is an
**open input**, not an assumption this document makes on the repository
owner's behalf: confirming the legacy version is a prerequisite for any
future TB CE upgrade-path planning (rule-chain export/import
compatibility, REST API version drift, data-model changes between the
legacy version and v4.3.1.5) and is out of Track A's own scope per ADR
0012 (Track A investigates the *target* version's extension surface,
not the legacy system's current state -- that is a separate, future
migration-scoping exercise, likely feeding Migration Studio per ADR
0002).

## 2. Extension API inventory at v4.3.1.5

### 2.1 `TbNode` / `@RuleNode` -- custom rule nodes

**Source:** [`TbNode.java`](https://github.com/thingsboard/thingsboard/blob/release-4.3/rule-engine/rule-engine-api/src/main/java/org/thingsboard/rule/engine/api/TbNode.java) and
[`RuleNode.java`](https://github.com/thingsboard/thingsboard/blob/release-4.3/rule-engine/rule-engine-api/src/main/java/org/thingsboard/rule/engine/api/RuleNode.java), `release-4.3` branch (confirmed present at
this path on that branch) | [Rule Node Development Guide](https://thingsboard.io/docs/user-guide/contribution/rule-node-development/).

**`TbNode` interface** (every custom rule node implements this):

```java
void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException;
void onMsg(TbContext ctx, TbMsg msg) throws ExecutionException, InterruptedException, TbNodeException;
default void destroy() { }
default void onPartitionChangeMsg(TbContext ctx, PartitionChangeMsg msg) { }
default TbPair<Boolean, JsonNode> upgrade(int fromVersion, JsonNode oldConfiguration) throws TbNodeException;
```

Event hooks a custom rule node can act on: `init` (node instantiation,
once per rule-chain deployment), `onMsg` (once per message the node
receives -- the actual per-event hook), `onPartitionChangeMsg` (cluster
rebalancing), `destroy` (node teardown), and `upgrade` (config-schema
migration between rule-node versions, driven by the `version` field on
`@RuleNode` -- directly analogous to this project's own event-envelope
versioning discipline, ADR 0012 Decision 4).

**Rule-chain context available (`TbContext`):**
[`TbContext.java`](https://github.com/thingsboard/thingsboard/blob/release-4.1/rule-engine/rule-engine-api/src/main/java/org/thingsboard/rule/engine/api/TbContext.java)
(fetched at `release-4.1`; the interface's shape is stable across the
4.x line per the rule-node development guide, but this specific citation
is one minor version behind the pinned target -- flagged under Gaps
below). Method categories:

- **Message routing**: `tellSuccess`, `tellFailure`, `tellNext`,
  `tellSelf`, `enqueue`, `enqueueForTellNext`.
- **Message construction**: `newMsg`, `transformMsg`, plus
  purpose-built factories (`deviceCreatedMsg`, `alarmActionMsg`,
  `attributesUpdatedActionMsg`).
- **Data access services**: `getDeviceService`, `getAttributesService`,
  `getCustomerService`, `getTenantService`, `getAlarmService`,
  `getTelemetryService`, `getTimeseriesService`, `getRelationService`,
  and related profile/credential services.
- **Execution/scheduling**: `schedule`, and dedicated
  `ListeningExecutor`s for mail, SMS, DB callbacks, external calls, and
  notifications.
- **Queue/notification**: `getQueueService`, `getQueueStatsService`,
  `getNotificationCenter`.
- **Tenant/system context**: `getTenantId`, `getSelfId`,
  `getRuleChainName`, `getServiceId`.

**`@RuleNode` annotation fields** (confirmed identical on both
`release-4.1` and `release-4.3`): `type`, `name`, `nodeDescription`,
`nodeDetails`, `configClazz`, `clusteringMode` (default `ENABLED`),
`hasQueueName`, `inEnabled`, `outEnabled`, `scope` (default `TENANT`),
`relationTypes` (default `SUCCESS`/`FAILURE`), `uiResources`,
`configDirective`, `icon`, `iconUrl`, `docUrl`, `customRelations`,
`ruleChainNode`, `ruleChainTypes`, `version`.

### 2.2 `AbstractIntegration` -- integration types and gRPC surface

**Finding, not a gap: this extension point does not exist in CE.**
[ThingsBoard's own Integrations documentation](https://thingsboard.io/docs/user-guide/integrations/)
states plainly: *"This feature is available in ThingsBoard Professional
and ThingsBoard Cloud only."* The pluggable `AbstractIntegration`
framework (custom/remote integrations communicating over a gRPC
executor, per
[`CustomIntegration.java`](https://github.com/thingsboard/remote-integration-example/blob/master/src/main/java/org/thingsboard/integration/custom/basic/CustomIntegration.java)
in ThingsBoard's own example repository) is a **Professional Edition /
Cloud-only** capability, never shipped in Community Edition.

This is a load-bearing finding for ADR 0011's own platform strategy,
not a minor detail: ADR 0011 Decision 8 named `AbstractIntegration` as
one of TB CE's extension points the platform would use. Since the
repository's committed direction is TB CE specifically (open-source,
never a paid edition -- ADR 0011 Decision 1), **any integration-style
external connector this platform needs must be built as an external
Java service talking to TB CE over its public REST API and/or its own
transport layer, not as a TB-CE-hosted `AbstractIntegration`
implementation.** This is actually consistent with, and reinforces,
ADR 0011 Decision 3's own preference ("prefer external Java services
over in-TB modifications") -- but it forecloses one option that
preference framing left open (an in-TB-hosted custom integration), so
it should be read into ADR 0011's own text at the next revision rather
than left as a Track-A-only footnote.

### 2.3 REST API -- auth model and key endpoints

**Source:** [ThingsBoard REST API reference](https://thingsboard.io/docs/reference/rest-api/)
(tracks the current stable release, i.e. v4.3.1.x at the time of this
document).

**Auth model**: JWT by default --

```
POST /api/auth/login
Content-Type: application/json
{"username": "...", "password": "..."}
```

returns `{"token": "...", "refreshToken": "..."}`. Subsequent requests
carry the token as `X-Authorization: Bearer <token>` (confirming ADR
0011's own assumption of this header name). Access tokens are valid 2.5
hours; refresh tokens one week.

**Since v4.3, API keys are the recommended alternative to JWT** for
service-to-service calls: `X-Authorization: ApiKey <key>`, long-lived,
revocable from the UI. This is directly relevant to how the Java
platform's own services authenticate to TB CE's REST API (external
Java services, per ADR 0011 Decision 3) -- API keys, not a JWT
login/refresh cycle, are the natural fit for a service account and
should be the default assumption for Track B's own TB-CE-facing client
code, pending confirmation once that code is actually written (M10+,
not Track A).

**Key endpoints:**

| Operation | Method | Endpoint |
| --- | --- | --- |
| List tenant devices | GET | `/api/tenant/devices?pageSize=10&page=0` |
| Get device telemetry | GET | `/api/plugins/telemetry/DEVICE/{id}/values/timeseries` |
| Post telemetry | POST | `/api/plugins/telemetry/DEVICE/{id}/timeseries/ANY` |
| Device attributes | GET/POST/DELETE | `/api/plugins/telemetry/DEVICE/{id}/attributes/{scope}` |
| Two-way RPC to device | POST | `/api/rpc/twoway/{deviceId}` |
| List rule chains | GET | `/api/ruleChains` |

### 2.4 Event hooks -- Kafka topics and serialization

**Source:** [`TransportProtos.proto`](https://raw.githubusercontent.com/thingsboard/thingsboard/release-4.3/common/proto/src/main/proto/queue.proto),
`release-4.3` branch (confirmed present at this path) |
[ThingsBoard Microservices architecture](https://thingsboard.io/docs/reference/msa/) |
[Cluster setup using Docker Compose](https://thingsboard.io/docs/user-guide/install/cluster/docker-compose-setup/).

**Kafka is a CE feature, not PE-only** -- distinct from 2.2's finding
about integrations. `TB_QUEUE_TYPE=kafka` is a documented CE
configuration option for clustered deployments (the in-memory queue is
explicitly unsupported for cluster mode: *"In Memory queue is **not**
suitable for cluster mode"*). The architecture-overview page
documenting the topic topology below lives under a `/docs/pe/...` URL,
but the queue backend selection itself, and the topics it produces,
apply identically when a CE cluster is configured with
`TB_QUEUE_TYPE=kafka`.

**Serialization: Protobuf** (`TransportProtos.proto`), not JSON.
Top-level message types confirmed present in that file at `release-4.3`:

- `ToRuleEngineMsg` -- carries a `TbMsgProto tbMsgProto` payload plus
  tenant ID (MSB/LSB split, not a string UUID), relation types, and an
  optional failure message. This is the actual per-rule-chain-message
  envelope on the wire.
- `ToCoreMsg` -- device actor operations, device state changes,
  subscription management, connectivity/lifecycle events.
- `TransportToRuleEngineMsg` -- carries session info plus one of
  `PostTelemetryMsg` / `PostAttributeMsg` / `ToDeviceRpcResponseMsg` /
  `ToServerRpcRequestMsg`.
- `ToTransportMsg`, `ToCoreNotificationMsg`, `ToEdgeMsg`,
  `TransportApiRequestMsg`/`TransportApiResponseMsg`,
  `ToUsageStatsServiceMsg`.

**Topics** (names per the microservices-architecture reference, which
documents the same queue regardless of edition once Kafka is the
selected backend): `tb_transport.api.requests` /
`tb_transport.api.responses` (transport <-> core, credential validation
and attribute fetch), `tb_rule_engine` (transport -> rule engine:
telemetry, attributes, RPC, lifecycle events), `tb_core` (rule engine ->
core: entity lifecycle, connectivity updates), plus `js.eval.requests` /
`js.eval.responses` for the separate JS-executor microservice (relevant
only if that microservice is deployed; the monolithic default install
does not need it).

This is directly relevant to ADR 0012 Decision 4 (the platform's own
Protobuf/CloudEvents envelope): TB CE's own internal bus is already
Protobuf-over-Kafka, which is one more point of alignment with the
platform's chosen format, but **TB CE's `TransportProtos.proto` schema
is TB CE's own internal wire format, not something this platform
consumes directly or maps its own envelope onto** -- any bridge between
the two happens at a defined boundary (an external Java service reading
TB CE's REST API or its own Kafka topics, translating into the
platform's own CloudEvents/Protobuf envelope), not by importing TB CE's
internal `.proto` definitions into the platform's own schema.

## 3. Draft `tb-extensions/manifest.yaml`

See [`tb-extensions/manifest.yaml`](../tb-extensions/manifest.yaml) in
this same PR. First version of the declarative customization surface
named in ADR 0011 Decision 8 -- intentionally thin (no custom rule
nodes or integrations are actually being built in Track A), but shaped
to be extended, not rewritten, once Track B and M10+ add real entries.

## 4. Representative TB CE event (synthetic)

One synthetic event, constructed to match the real `ToRuleEngineMsg` /
`TbMsg` shape described in 2.4 above -- not captured from any real
system, since this document has no access to a real TB CE deployment.
Every identifier below is fabricated; none is a real tenant, device,
customer, or credential value, per ADR 0012 Decision 2.

```json
{
  "id": "5f1b6b1a-0000-4a11-8e11-000000000001",
  "type": "POST_TELEMETRY_REQUEST",
  "originator": {
    "entityType": "DEVICE",
    "id": "5f1b6b1a-0000-4a11-8e11-000000000002"
  },
  "customerId": "5f1b6b1a-0000-4a11-8e11-000000000003",
  "tenantId": "5f1b6b1a-0000-4a11-8e11-000000000004",
  "ruleChainId": "5f1b6b1a-0000-4a11-8e11-000000000005",
  "ruleNodeId": "5f1b6b1a-0000-4a11-8e11-000000000006",
  "clusterPartition": 0,
  "metadata": {
    "deviceName": "synthetic-tank-sensor-01",
    "deviceType": "tank-level-sensor",
    "ts": "1790000000000"
  },
  "data": {
    "ts": 1790000000000,
    "values": {
      "tank_level_percent": 62.5,
      "temperature_c": 18.2
    }
  },
  "dataType": "JSON"
}
```

This shape follows `TbMsg`'s documented fields (id, type, originator,
customer/tenant IDs, rule-chain/rule-node IDs, metadata, data, data
type) as referenced by the rule-node development guide and
`TbMsgGeneratorNode.java`'s own construction pattern, rather than an
exact byte-for-byte reproduction of `TransportProtos.proto`'s binary
encoding (a JSON rendering of the logical message is far more useful
here than an opaque Protobuf byte string, and this document makes no
claim that the bytes above are what would appear on the wire).

## 5. Gaps and unknowns

Could not be determined from public TB CE documentation and source
alone, and carried forward as input for Track A follow-up or M10:

- **Legacy TB CE version** (see Section 1) -- requires the repository
  owner or direct access to the SpectroTANK deployment; not
  determinable from public sources.
- **`TbContext`'s exact shape at v4.3.1.5.** The citation in 2.1 is
  from `release-4.1`, one minor version behind the pinned target,
  because a fetch of the same file at `release-4.3` was not attempted
  during this pass; the rule-node development guide states the
  interface is stable across the 4.x line, but this should be verified
  directly against `release-4.3` (or the `v4.3.1.5` tag specifically)
  before Track B writes any code that depends on a specific `TbContext`
  method signature.
- **Exact Kafka topic list for the CE + `TB_QUEUE_TYPE=kafka`
  configuration at v4.3.1.5, in full**, including any topics added or
  renamed since the architecture-overview page (a PE-hosted URL) was
  last updated relative to the CE-specific docs. The topics listed in
  2.4 are very likely correct (the underlying queue module is shared
  code, not edition-forked), but this document did not find a
  CE-specific, version-pinned topic list to cross-check against.
  Confirming this against a real running v4.3.1.5 instance (`kafka-
  topics.sh --list`, or the JMX/queue-stats API) is a natural Track A
  follow-up rather than something resolvable from documentation alone.
- **Whether v4.3.1.5's Calculated Fields feature (new in 4.3, per the
  release announcement) changes the rule-chain execution model in any
  way relevant to this platform's own event/command envelope
  (ADR 0012 Decision 4).** Not investigated in this pass; flagged as a
  candidate for Track A follow-up before Track B's `common/` envelope
  design is treated as final for TB-CE-facing consumers.
- **Whether ThingsBoard offers any officially-designated "LTS" release
  line**, as distinct from "current stable." Not found in the sources
  consulted; the version-pinning strategy in Section 1 assumes there is
  none, and treats the current stable minor line (`4.3.x`) as the
  pin target on that basis. If an LTS designation exists and was
  missed, it would change that recommendation.
- **Confirmation that this document's TB CE citations were fetched from
  the real, current state of `thingsboard/thingsboard`'s public GitHub
  repository and `thingsboard.io` documentation site**, rather than a
  cached or approximated rendering. Every URL cited above is a real,
  addressable ThingsBoard-owned source; the repository owner's own spot
  check of at least the version-pin and the `AbstractIntegration`
  PE-only finding (Section 2.2, since it changes the platform's own
  integration strategy) is recommended before this document is treated
  as final.
