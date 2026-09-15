# Essentials 0.50.0 — Release Notes

_Covers everything on `main` since the `0.40.28` tag: 33 commits, 1510 files, +137k/−7k lines._

0.50.0 is the largest release since Trustworks took over the project. It moves the whole stack forward one
generation — **Java 21, Spring Boot 4.0, Jackson 3** — replaces the bundled Vaadin admin UI with a published
HTTP contract, adds a **CDC-backed EventStore**, adds **aggregate snapshots and Closing the Books**, and pulls
the example applications into this repository so they compile against the sources rather than a released
version.

It also begins a deliberate, two-release API cleanup: 137 wide and `Optional`-taking constructors are now
`@Deprecated(forRemoval = true)` with a builder alongside each one. **Nothing was removed in 0.50.0.** The
removals happen in **0.60.0** — see [§4](#4-what-0600-will-break) and start migrating now.

| | 0.40.28 | 0.50.0 |
|---|---|---|
| Java | 17+ | **21+** (`--release 21`; build on JDK 21–25) |
| Spring Boot | 3.3.13 | **4.0.8** |
| Jackson | 2 only | **3 by default**, 2 still supported |
| Kotlin | 2.1 | **2.2** (stdlib API level held at 2.1) |
| Testcontainers | 1.x | **2.0.x** |
| Admin UI | Vaadin | OpenAPI contract + Thymeleaf/vanilla-JS console |
| Examples | separate repos | in this reactor |
| Modules | 28 | 35 (32 published + 3 examples) |

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes — verify your configuration](#11-️-silent-behaviour-changes--verify-your-configuration-read-this-first)
   - [1.2 Java 21 is a hard floor](#12-java-21-is-a-hard-floor)
   - [1.3 Spring Boot 4.0.x](#13-spring-boot-40x)
   - [1.4 Jackson 3 is the default flavour](#14-jackson-3-is-the-default-flavour)
   - [1.5 The Vaadin admin UI is gone](#15-the-vaadin-admin-ui-is-gone)
   - [1.6 Kotlin 2.2 binary metadata](#16-kotlin-22-binary-metadata)
   - [1.7 Declare your aggregates](#17-declare-your-aggregates-if-you-use-policy-annotations)
   - [1.8 Testcontainers 2.x](#18-testcontainers-2x-test-scope-only)
2. [New features](#2-new-features)
3. [Deprecations — removed in 0.60.0](#3-deprecations--removed-in-0600)
4. [What 0.60.0 will break](#4-what-0600-will-break)
5. [Module inventory](#5-module-inventory)
6. [Reference](#6-reference)

---

## 1. Breaking — what you MUST apply

### 1.1 ⚠️ Silent behaviour changes — verify your configuration (read this first)

Everything else in this section fails loudly at compile time or startup. **These three do not.** They compile
clean, start clean, and change runtime semantics. Check them before anything else.

#### 1.1.1 `PostgresqlDurableQueues` constructors now default to `SingleOperationTransaction`

Constructors that do **not** name a `TransactionalMode` hardcoded `FullyTransactional`, while
`PostgresqlDurableQueues.builder()` defaulted to `SingleOperationTransaction` — the same component behaved
differently depending on how you created it. 0.50.0 closes the divergence by converging on the builder's
defaults (`SingleOperationTransaction`, 30-second `messageHandlingTimeout`). `FullyTransactional` is the side
documented as broken for retries and dead-lettering, which is why convergence goes this way.

**If you construct `PostgresqlDurableQueues` through a mode-less constructor and rely on `FullyTransactional`,
name it explicitly:**

```java
new PostgresqlDurableQueues(unitOfWorkFactory, jsonSerializer, tableName, listener, optimizerFactory,
                            TransactionalMode.FullyTransactional, null);
```

> In `SingleOperationTransaction` the consumer **must** acknowledge messages explicitly in a new `UnitOfWork` —
> see `DurableQueues#acknowledgeMessageAsHandled`. A consumer written for `FullyTransactional` that never
> acknowledges will redeliver forever.

#### 1.1.2 `MongoDurableQueues.builder()` now defaults to `SingleOperationTransaction`

The same convergence, one layer out: the two builders now agree. `MongoDurableQueues.builder()` previously
produced `FullyTransactional` (it delegated to the constructor taking a
`SpringMongoTransactionAwareUnitOfWorkFactory`). An application that moved between the two database modules got
different delivery semantics from identical code, with nothing in either API saying so.

`messageHandlingTimeout` now defaults to `MongoDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT` (30 seconds),
matching PostgreSQL, so the default mode is usable with nothing but a `MongoTemplate`.

```java
MongoDurableQueues.builder()
                  .setMongoTemplate(mongoTemplate)
                  .setJsonSerializer(jsonSerializer)
                  .setSharedQueueCollectionName("durable_queues")
                  .setTransactionalMode(TransactionalMode.FullyTransactional)
                  .setUnitOfWorkFactory(unitOfWorkFactory)   // required in this mode
                  .build();
```

The **`MongoDurableQueues` constructors are unaffected** — each still produces the mode its javadoc names. Only
builder callers see this.

Both defaults are now pinned by `PostgresqlDurableQueuesBuilderDefaultsTest` and
`MongoDurableQueuesBuilderDefaultsTest`.

#### 1.1.3 `PostgresqlDurableQueues.builder()` now defaults `useOrderedUnorderedQuery` to `true`

`EssentialsComponentsProperties` defaulted the flag to `true` and the deprecated wide constructors passed
`true`, but `PostgresqlDurableQueuesBuilder`'s uninitialised `boolean` field left it `false`. Spring
applications got the split ordered/unordered fetch queries; anyone using the builder — the documented preferred
path — silently got the single unified query.

That unified query applies the ordered per-key barrier (a correlated `NOT EXISTS` against the same table) to
every candidate row, including unordered rows where `key IS NULL` makes it vacuously true, and orders by
`key_order`, a constant `-1` for those rows. On a backlog mixing both kinds it measured **5.4× slower**.

**No action needed for Spring applications** — they were already on `true`. If you build
`PostgresqlDurableQueues` directly and deliberately want the unified query, say so:

```java
PostgresqlDurableQueues.builder()
                       .setUnitOfWorkFactory(unitOfWorkFactory)
                       .setUseOrderedUnorderedQuery(false)
                       .build();
```

The flag is now readable at runtime via `PostgresqlDurableQueues.isUseOrderedUnorderedQuery()`, so a deployment
can verify which fetch strategy it actually got rather than the one it believes it configured.

---

### 1.2 Java 21 is a hard floor

Artifacts are compiled with `--release 21`, so the class files carry major version 65 and **a Java 17 runtime
rejects them with `UnsupportedClassVersionError`**. This is not a recommendation.

- **Runtime:** Java 21 or later.
- **Building Essentials itself:** JDK 21–25 (`maven-enforcer` pins `[21,26)`); CI builds on JDK 21 and 25.
- Your own modules may still target 17 as long as they *run* on 21+, but there is no reason to.

### 1.3 Spring Boot 4.0.x

The starters resolve `org.springframework.boot:spring-boot-dependencies:4.0.8`. **Spring Boot 3.x is no longer
supported** — 4.0 moved to Jackson 3 and Jakarta EE 11, so a 3.x application cannot consume these starters
unchanged.

The relocations and renames that bit this repository during the upgrade will bite yours in the same places:

| What | 3.x | 4.0 |
|---|---|---|
| Health contributor API | `org.springframework.boot.actuate.health.{HealthIndicator,Health,Status}` | `org.springframework.boot.health.contributor.*` — in the new `spring-boot-health` module, which `spring-boot-actuator-autoconfigure` does **not** depend on; declare it explicitly |
| `@ConditionalOnEnabledHealthIndicator` | `…actuate.autoconfigure.health` | `org.springframework.boot.health.autoconfigure.contributor` |
| DataSource auto-configuration | `org.springframework.boot.autoconfigure.jdbc.{DataSourceProperties,DataSourceAutoConfiguration,DataSourceTransactionManagerAutoConfiguration}` | `org.springframework.boot.jdbc.autoconfigure.*` |
| Mongo connection properties | `spring.data.mongodb.*` | **`spring.mongodb.*`** |
| Actuator endpoint toggles | `management.endpoint.<id>.enabled` | `management.endpoint.<id>.access` |
| AOP starter | `spring-boot-starter-aop` | `spring-boot-starter-aspectj` |
| Tracing | hand-picked OTLP registry + bridge + exporter | `spring-boot-starter-opentelemetry` (actuator no longer carries tracing auto-config) |
| Kafka JSON (de)serializers | Jackson 2 `JsonSerializer`/`JsonDeserializer` | `JacksonJsonSerializer`/`JacksonJsonDeserializer` (Jackson 2 pair deprecated for removal) |

> **The Mongo property rename is the dangerous one.** The old names are deprecated at level `error`, meaning
> they are **no longer bound** — no warning, no failure. `MongoProperties` silently falls back to
> `mongodb://localhost/test`, and every operation then spends 60 seconds waiting for a server that is not there
> before failing with `MongoTimeoutException`. Grep your configuration for `spring.data.mongodb`.

### 1.4 Jackson 3 is the default flavour

`types-jackson3` / `immutable-jackson3` (Jackson 3, group `tools.jackson.core`) are the default, matching
Spring Boot 4. `types-jackson` / `immutable-jackson` (Jackson 2, group `com.fasterxml.jackson.core`) remain
supported.

Both flavours publish **the same class names**, so exactly one may be on the classpath. The components modules
pull Jackson 3 transitively; to stay on Jackson 2, exclude it and declare the Jackson 2 flavour yourself:

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-event-store</artifactId>
    <version>${essentials.version}</version>
    <exclusions>
        <exclusion>
            <groupId>dk.trustworks.essentials</groupId>
            <artifactId>types-jackson3</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

> **The Jackson 2 flavour is a migration aid, not a permanent lane.** It is deprecated as of 0.60.0 and will be
> removed in a later release — see [§4.4](#44-the-jackson-2-flavour-is-deprecated--removal-after-0600). Plan the
> move rather than pinning the exclusion above indefinitely.

**Your persisted data is safe.** Both majors write byte-identical JSON, enforced by golden wire-format tests
that run under both flavours — data written by a Jackson 2 deployment stays readable after moving to Jackson 3.
That guarantee holds only if every mapper used for persistence is built through `EssentialsObjectMappers` /
`EssentialsJSONEventSerializers`. A hand-assembled `ObjectMapper` drifts from the contract.

#### Two things to check in your own event and payload classes

Both are silent on the way **out** and only fail on the way **in**, which means an upgraded service can write
happily for days and then fail on replay.

**1. Constructor parameter *names* are now part of the JSON contract.** Jackson 3 reads them from the bytecode
and treats any constructor as an implicit properties-based creator — even when a no-arg constructor exists.
Jackson 2 registered no parameter-names module and populated fields instead. A parameter whose name differs
from the JSON property it ends up in receives `null`, so the object either trips its own `requireNonNull` guard
or comes back half-populated.

```java
// Breaks under Jackson 3: the parameter is "priceValidity", the persisted property is "priceValidityPeriod"
public InitialPriceSet(ProductId forProduct, PriceId priceId, Money price, TimeWindow priceValidity) {
    super(forProduct, priceId, priceValidity);   // assigns this.priceValidityPeriod
}
```

Two shapes bite: a parameter named differently from the field it assigns, and a parameter that is not a
property at all because the value is routed elsewhere — the classic `Event<ID>` subclass taking `orderId` and
calling `aggregateId(...)`, which persists as `aggregateId`. Fix on the type: rename the parameter, or annotate
it `@JsonProperty("…")` — that annotation lives in `com.fasterxml.jackson.annotation`, which **both** majors
share.

> `ConstructorDetector.EXPLICIT_ONLY` does *not* avoid this. With no other way to construct the type, Jackson 3
> uses the sole constructor regardless.

**2. `@JsonDeserialize(keyUsing = …)` stops applying.** It lives in Jackson 2's
`com.fasterxml.jackson.databind.annotation` package, which Jackson 3 does not read — so on upgrade it silently
stops working. For maps keyed by an Essentials value type you no longer need it at all: `types-jackson3`
registers `SingleValueTypeKeyDeserializers`. For other key types, switch the import to
`tools.jackson.databind.annotation`.

### 1.5 The Vaadin admin UI is gone

`components/vaadin-ui` and the Vaadin-based `spring-boot-starter-admin-ui` have been removed. See
[§2.2](#22-a-contract-first-admin-api) for what replaced them. Two consequences:

- **`spring-boot-starter-admin-ui` keeps its artifactId but is an entirely new implementation** (Thymeleaf
  shell + vanilla JavaScript, served at `/essentials/admin`). Any Vaadin view, route, theme or component you
  wrote against the old starter has no counterpart and must be rebuilt — against the HTTP contract, not against
  in-process beans.
- **Admin access is now deny-by-default and you must implement two SPIs.** The API authenticates nobody and
  depends on no security stack:

  | SPI | Answers |
  |---|---|
  | `dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser` | Who is calling? (`401` if unauthenticated) |
  | `dk.trustworks.essentials.shared.security.EssentialsSecurityProvider` | May this principal do this? (`403` if denied) |

  Both default to no-access implementations, so an application implementing neither exposes nothing — and the
  starter logs a warning at startup while those defaults are in place, because the API includes destructive
  operations such as purging a queue. How the request was authenticated (session, bearer token, mTLS, gateway
  header) is entirely your business.

Roles are per operation: `essentials_lock_reader`/`_writer`, `essentials_queue_reader`/`_writer`,
`essentials_scheduler_reader`, `essentials_subscription_reader`, `essentials_postgresql_stats_reader`.
`essentials_admin` satisfies every operation. Each operation carries its own `x-required-roles` in the
contract.

The whole build stays on the JVM: no Node, npm, bundler or JavaScript framework is involved in building,
validating or serving any of it.

### 1.6 Kotlin 2.2 binary metadata

Kotlin artifacts are compiled at language level **2.2**, which sets the emitted `@Metadata` binary version — a
Kotlin **2.1** compiler rejects them as an incompatible binary version. The stdlib API level is deliberately
held one behind, at 2.1, so the runtime requirement is looser than the compiler requirement.

### 1.7 Declare your aggregates (if you use policy annotations)

This one raises **no error** when you get it wrong, which is why it is here rather than in the features section.

`@AggregateSnapshotPolicy` and `@AggregateClosingBooksPolicy` are registered by `BeanPostProcessor`s, which only
observe Spring beans. An aggregate root is not a Spring bean and never should be — a singleton `Order` is
meaningless — so annotations on an aggregate class reach no registry, the admin API's lifecycle endpoints report
nothing, and nothing complains.

Declare them explicitly:

```java
@Bean
EssentialsAggregateDeclarations tradingAggregates() {
    return EssentialsAggregateDeclarations.builder()
                                          .declare(TRADING_ACCOUNTS,  TradingAccount.class)
                                          .declare(INSTRUMENT_PRICES, InstrumentPrice.class)
                                          .declare(SETTLEMENTS,       Settlement.class)
                                          .build();
}
```

Any number of `EssentialsAggregateDeclarations` beans may exist; all are merged. Declaring one implementation
class for two different `AggregateType`s is rejected — the registries are keyed by implementation class, so the
second declaration would silently displace the first. `EssentialsAggregateDeclarations` lives in
`eventsourced-aggregates` (Spring-free), so non-Spring users get the same path.

### 1.8 Testcontainers 2.x (test scope only)

If you depend on `components/foundation-test`, note that Testcontainers moved to 2.0.x and the artifact names
changed: `testcontainers-postgresql`, `testcontainers-mongodb`, `testcontainers-kafka`.

---

## 2. New features

### 2.1 CDC EventStore — logical replication with polling fallback

A full PostgreSQL logical-replication path for the EventStore, combining sub-second live delivery with the
polling guarantees the event store already had. **`pgoutput` is the default plugin; `wal2json` is optional.**

- **Live:** WAL → `WalReplicationTailer` → inbox (`eventstore_cdc_inbox`) or direct → `CdcDispatcher` →
  in-memory event bus → subscribers, at low-millisecond latency.
- **Backfill:** standard EventStore polling, used both to bootstrap a subscription's resume point and as a
  fallback whenever CDC is unhealthy.
- **Ordered handover:** `BackfillThenLiveOrdered` snapshots the head global order at subscription time, polls
  `[resume … head]`, then gates live emissions until backfill catches up — subscribers see a strictly monotonic
  global-order stream across the boundary.

What CDC does **not** change: the EventStore is still the source of truth. CDC is a delivery accelerator. If it
is `INACTIVE` or `FAILED`, subscribers fall back to polling and stay correct. Resume points remain durable in
the EventStore, not in CDC state.

**CDC is disabled by default.** Nothing is wired unless you ask: no slot is created, no publication is touched,
no tailer connects, no inbox table is used.

```yaml
essentials:
  eventstore:
    cdc:
      enabled: true          # opt in — no CDC bean exists without this
      pg-output:
        publication:
          auto-manage: true
          mode: FOR_TABLE_LIST   # explicit list; needs table ownership, not superuser
```

Database-side prerequisites: `wal_level = logical` (server restart; on RDS set `rds.logical_replication` and
reboot), headroom in `max_replication_slots` and `max_wal_senders` (one slot per pipeline), a role with
`REPLICATION`, and — for `pgoutput` — a publication covering the event-stream tables.

> With `cdc.mode=auto` (the default once enabled) a missing prerequisite keeps the application up and
> subscribers on polling. **A broken CDC setup costs latency, not correctness — and is therefore easy to miss.**
> Verify via `/actuator/health/cdc` or the admin API's `GET /event-store/cdc/status`. Read `everActive=false`
> with a non-zero `warmupPollCount` as "CDC never came up"; `fallbackCount` (polls *after* CDC had been active)
> is the alertable signal.

Operational model: an advisory lock per slot ensures one active tailer per slot; `CdcMode` controls startup
semantics (`require` fails startup, `auto` degrades); `PgSlotMode` controls slot lifecycle
(`CREATE_IF_MISSING`, `REQUIRE_EXISTING`, `RECREATE`, `EXTERNAL`). Conversion failures mark the inbox row
`POISON`, extract the affected global orders and register them as permanent gaps; `CdcPoisonNotifier` (e.g.
`SubscriptionResetOnPoisonNotifier`) can reset resume points backward.

Metrics: `essentials.cdc.active`, `…fallback_total`, `…warmup_poll_total`, `…start_failures_total`, plus
per-slot gauges from `CdcSlotMetrics`.

Full design and operations guide: [`docs/cdc.md`](cdc.md).

### 2.2 A contract-first admin API

The removed Vaadin UI is replaced by a **published HTTP contract**, so applications can generate their own
client or build their own console.

| Module | What it is |
|---|---|
| `admin-api-spec` | The OpenAPI contract — 40 operations, generated **code-first** from the in-process `*Api` SPIs. Published as `:yaml:openapi` and on the jar classpath at `/openapi/essentials-admin-api.yaml` |
| `spring-boot-starter-admin-api` | Serves the contract over Spring WebMVC. Auto-configured *once on the classpath* — but see the note below: no other Essentials starter puts it there |
| `admin-api-client-java` | Java client generated from the contract (`java` / `native` library) |
| `spring-boot-starter-admin-ui` | Optional default console — Thymeleaf shell + vanilla JavaScript at `/essentials/admin` |

Endpoints mount under `/api/essentials/admin/v1` (configurable), covering fenced locks, the scheduler
(`pg_cron`), PostgreSQL query statistics, durable queues (including message get/delete/resurrect/mark-as-dead-letter
and purge), event-store subscriptions and their statistics, CDC status, and event-store table statistics.

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-admin-api</artifactId>
</dependency>
```

> **You must add this dependency yourself.** No other Essentials starter depends on it —
> `spring-boot-starter-postgresql`, `spring-boot-starter-postgresql-event-store` and
> `spring-boot-starter-mongodb` do **not** pull it in, so an application that adds none of the admin modules
> serves no admin endpoints at all. The one exception is `spring-boot-starter-admin-ui`, which depends on the
> API starter at compile scope: adding the UI gives you the API transitively, and adding only the API gives you
> the endpoints with no bundled console.
>
> Once it *is* on the classpath nothing further is needed to register it — the starter ships
> `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` pointing at
> `EssentialsAdminApiAutoConfiguration`, so there is no `@Import` to write. That is the opposite of
> `types-spring-web`, which ships no `.imports` file and therefore *does* need an explicit `@Import` — see
> [§2.6](#26-shipped-spring-web-configurers-for-semantic-types).
>
> Two gates, not one: adding the dependency wires the endpoints, but they still expose nothing until
> `EssentialsAuthenticatedUser` and `EssentialsSecurityProvider` are implemented — see
> [§1.5](#15-the-vaadin-admin-ui-is-gone).

```yaml
essentials:
  admin-api:
    enabled: true                            # false exposes nothing
    base-path: /api/essentials/admin/v1      # relocate behind a gateway prefix
```

**The bundled UI is a client of the contract, not a shortcut around it.** It runs in the same JVM as the `*Api`
SPI beans, so a Thymeleaf controller could simply inject `DurableQueuesApi` and render queue data server-side.
It deliberately does not: `AdminUiController` serves only the shell page — its entire state is three fields
(UI properties, API properties, the authenticated user) — and every piece of data on the page comes from
`admin.js` calling the same HTTP paths an external client would.

That constraint buys two things:

- **The contract is provably sufficient.** The console is real and non-trivial, so if the contract were missing
  an operation or a field the console needed, the console could not have been built. Had the UI reached the
  beans directly, it could look feature-complete while the contract had holes in it.
- **The contract cannot silently fall behind.** A UI reading the SPI directly would be a second consumer, free
  to gain a capability that never reaches the contract. Over HTTP, a UI feature has to exist in the contract
  first.

`AdminUiContractParityTest` enforces exactly this: every path the UI calls must be declared by the contract,
every contract path must be surfaced by the UI, and `admin.js` may not name an SPI type while
`AdminUiController` may not grow a fourth field — so the shortcut cannot be reintroduced quietly.

It is one of six build gates keeping the three copies of every admin operation (the `*Api` SPI, the
`EssentialsAdminApiSpec` mapping table, and the controller) in agreement; the others are contract drift,
backward compatibility via openapi-diff, spec validation, controller conformance, and auto-configuration
registration.

Versioning is path-major: breaking changes ship as `/api/essentials/admin/v2` served side by side, so `v1`
consumers and their generated clients keep working. `5xx` bodies carry no detail — causes are logged instead,
so internal failures cannot leak schema names, SQL or hostnames.

See [`LLM/LLM-admin-api.md`](../LLM/LLM-admin-api.md) and [`docs/openapi/README.md`](openapi/README.md).

### 2.3 Aggregate snapshots and Closing the Books

Two related mechanisms for keeping long-lived aggregates loadable.

**Snapshots** save aggregate state at `EventOrder` N so a load replays only events after N. Available
hand-wired via `PostgresqlAggregateSnapshotRepository`, or policy-driven:

```java
@AggregateSnapshotPolicy(aggregateType = "Orders",
                         mode = SnapshotExecutionMode.ASYNC_DURABLE,
                         everyNEvents = 100,
                         deletionMode = SnapshotDeletionMode.KEEP_LAST_N,
                         keepLastSnapshots = 3)
public class Order extends AggregateRoot<OrderId, OrderEvent, Order> { }
```

`SYNC`, `ASYNC_IN_MEMORY` and `ASYNC_DURABLE` execution modes; `DELETE_ALL_HISTORIC` or `KEEP_LAST_N` deletion.
The durable path is hardened against the failure modes that matter: saves carry a version guard and a bounded
delete so an out-of-order async worker cannot resurrect a stale snapshot or wipe a newer one; a
`processing_started_ts` column lets `lockNextBatch` reclaim rows whose worker is presumed dead
(`DurableAsyncSnapshotSettings.processingTimeout`, default 5 min); delete + save + mark-completed run in a
single `UnitOfWork`; and retry-exhausted jobs move to a `PARKED` status that a corrected payload can replace
via `ON CONFLICT DO UPDATE`.

**Closing the Books** keeps streams short by closing a generation and opening the next, rather than letting one
stream grow without bound:

```java
@AggregateClosingBooksPolicy(aggregateType = "Accounts",
                             triggerMode = ClosingBooksTriggerMode.SCHEDULED_SCAN,
                             defaultPolicy = ClosingBooksDefaultPolicyType.EVENT_COUNT_OR_TIME_BOUNDARY,
                             timeBoundary = ClosingBooksTimeBoundary.END_OF_MONTH)
public class Account extends AggregateRoot<String, AccountEvent, Account> { }
```

Trigger modes `ON_ACCESS`, `EXPLICIT_COMMAND`, `SCHEDULED_SCAN`; time boundaries `END_OF_DAY`, `EVERY_N_DAYS`,
`END_OF_MONTH` and friends; decisions `KEEP_OPEN`, `CLOSE_ONLY`, `CLOSE_AND_OPEN_NEXT`, composable through
`ClosingBooksDecisionPolicies.anyOf/allOf/when/whenTriggeredBy`. Rollovers are instrumented in
`ClosingBooksCoordinator` — the one class every trigger mode passes through — with a rollover timer,
`generations_closed` / `generations_opened` / outcome / decision counters and a `last_rollover_epoch_ms` gauge.
Counters increment *after* the `UnitOfWork` returns, so a rollback cannot leave a count claiming a generation
was closed.

Ergonomics landed alongside: a single `ClosingBooksIdSerializer<ID>` (replacing the two identically-shaped
serializer interfaces, with the persisted form unchanged), and `ClosingBooksIdSerializer.forType(Class)` which
derives the strategy from the id type — `String`, `UUID`, enum, a `CharSequence`-backed `SingleValueType`, a
number- or temporal-backed `SingleValueType`, or any type with a `(String)` constructor, a static `of(String)`
or a static `from(String)`. The strategy resolves once, at `forType(...)` time, and an unhandleable id type
throws immediately with a message naming the type and the creator shapes searched for — failing at startup
rather than during a generation resolve much later.

Both features are surfaced by the admin API's aggregate-lifecycle endpoints.

### 2.4 Runtime subscription statistics

The event store now counts what its subscriptions are doing at runtime, and the admin API surfaces it. The two
halves are separate modules and can be adopted separately:

| Concern | Module | Where |
|---|---|---|
| **Collection** | `postgresql-event-store` | `SubscriptionStatisticsRegistry` + `StatisticsCollectingEventStoreSubscriptionObserver` (`…eventstore.postgresql.observability`), wired by the event-store starter |
| **In-process access** | `postgresql-event-store` | The `EventStoreApi` SPI — its bean is declared by `spring-boot-starter-postgresql-event-store`, so it is reachable without any HTTP layer |
| **HTTP access** | `spring-boot-starter-admin-api` | `EventStoreController` |

Collection is **on by default** and happens whether or not anything exposes it. Turn it off with:

```yaml
essentials:
  eventstore:
    subscription-manager:
      statistics:
        enabled: false      # collection off; the endpoints below then answer empty/404
```

The HTTP endpoints exist **only if `spring-boot-starter-admin-api` is on the classpath** (see
[§2.2](#22-a-contract-first-admin-api)) and, like every admin path, are relative to
`essentials.admin-api.base-path` — so with the defaults:

```
GET /api/essentials/admin/v1/event-store/subscriptions
GET /api/essentials/admin/v1/event-store/subscriptions/statistics
GET /api/essentials/admin/v1/event-store/subscriptions/{subscriberId}/aggregate-types/{aggregateType}/statistics
```

Both statistics operations require the `essentials_subscription_reader` role.

> **Read these together with `active`/`lock`, never alone.** `GET /event-store/subscriptions` reports resume
> points from the database, so it lists every instance's subscriptions, while the statistics are counted
> in memory by the instance that answers. A subscription running elsewhere has no statistics here, and an
> exclusive subscription only handles events while it holds its fenced lock — a zero counter is not a stall.

### 2.5 Jackson 3 flavour modules

`types-jackson3` and `immutable-jackson3` are new, sharing FQCNs with their Jackson 2 counterparts so exactly
one flavour is ever on the classpath. Two Jackson 3 behaviours needed per-type handling and are dealt with
inside the framework rather than by annotating Essentials types:

- Jackson 3 disabled final-field mutation (Jackson 2's default), which is how immutable payloads get
  populated — `EssentialsObjectMappers` re-enables it.
- That in turn makes a type which *is* a collection or scalar wrapper look like a bean, so those are pinned to
  delegating creators: `Jackson3CollectionWrapperModule` (foundation, by shape) and
  `SingleValueTypeCreatorIntrospector` (`types-jackson3`).
- `SingleValueTypeKeyDeserializers` handles map keys typed as Essentials value types, so those need no
  annotation at all under Jackson 3.

### 2.6 Shipped Spring Web configurers for semantic types

`types-spring-web` now ships the configurer itself. At 0.40.28 the module contained only
`SingleValueTypeConverter` and its README handed you a `WebMvcConfig` / `WebFluxConfig` snippet to copy into
your own codebase; those were templates, never shipped API. 0.50.0 adds three production classes:

| Class | Role |
|---|---|
| `EssentialsWebMvcConfigurer` | `WebMvcConfigurer` for a servlet application — implements `addFormatters` only |
| `EssentialsWebFluxConfigurer` | The WebFlux equivalent |
| `KotlinValueTypeConverterRegistrar` | Registers the Kotlin semantic-type converter, guarded by `ClassUtils.isPresent` so Java-only consumers do not hit `NoClassDefFoundError` (`kotlin-reflect`/`kotlin-stdlib` are `<optional>`) |

```java
@SpringBootApplication
@Import(EssentialsWebMvcConfigurer.class)   // or EssentialsWebFluxConfigurer on a reactive application
public class Application { }
```

```java
@GetMapping("/orders/{orderId}")
public Order getOrder(@PathVariable OrderId orderId) { … }
```

> **This is not auto-configuration, and that is deliberate.** `types-spring-web` ships **no**
> `META-INF/spring/…AutoConfiguration.imports`, so declaring the dependency changes nothing on its own — you
> must `@Import` a configurer. Skipping it is the usual cause of "why is my typed `@PathVariable` a 500":
> a missing converter raises `ConversionNotSupportedException`, which Spring classes as server
> misconfiguration rather than a bad request.

Both configurers implement `addFormatters` and **nothing else** — deliberately. Neither touches the HTTP
message converters or codecs, so adding the module cannot change whichever Jackson major your application
serialises request and response *bodies* with. Bodies remain a separate concern, handled by
`EssentialTypesJacksonModule` from `types-jackson`/`types-jackson3` registered on the **web** `ObjectMapper` —
which no Essentials starter does for you. The converters cover `@PathVariable` and `@RequestParam` only.

**Existing consumers need do nothing.** A hand-written `WebMvcConfigurer` that registers
`SingleValueTypeConverter` keeps working exactly as before; the shipped classes let you delete it.

### 2.7 Java-first interoperability for `postgresql-document-db`

The Kotlin-based `postgresql-document-db` module gained a Java-friendly surface without breaking its existing
Kotlin APIs.

### 2.8 Durable queue fetch performance

`PostgresqlDurableQueues` now deduplicates message keys during fetch.

**Batched multi-queue fetching is now usable, and is opt-in.** `CentralizedMessageFetcher` can collapse its
one-query-per-active-queue polling into a single query across all of them. The code shipped in 0.40.x but its
only call site was commented out, so no released version ever ran it; 0.50.0 makes it reachable, behind a flag
that defaults to off:

```yaml
essentials:
  durable-queues:
    use-batched-fetch: true               # default false — per-queue fetching regardless of queue count
    batched-fetch-switch-threshold: 4     # only consulted when enabled: per-queue at or below, batched above
```

or `PostgresqlDurableQueues.builder().setUseBatchedFetch(true).setBatchedFetchSwitchThreshold(4)`.

The threshold default of 4 comes from the queue-fetch-strategy benchmark, which found the batched path pays off
above roughly four active queues.

> **The two strategies do not select identical messages.** Per-queue fetching is **ordered-priority**; batched
> fetching is **oldest-first**. That is a selection-order difference, not a correctness one, but it is the
> reason this is opt-in rather than a threshold the framework crosses on your behalf.

See also the `useOrderedUnorderedQuery` default change in [§1.1.3](#113-postgresqldurablequeuesbuilder-now-defaults-useorderedunorderedquery-to-true).

### 2.9 Examples now live in this repository

Three example applications build as part of the reactor, so they always compile and test against the sources in
the working tree rather than against a released version. They are **excluded from the release** — they are
demos, not published artifacts.

| Module | What it demonstrates |
|---|---|
| `examples/essentials-trading-demo` | **New.** Snapshots, Closing the Books, typed logical ids vs internal generation ids, starter auto-configuration in a realistic app. Two bounded contexts (`brokerage`, `market_data`), packaged by vertical slice — 24 command slices, 8 view slices — plus a demo harness with a load generator and dashboard |
| `examples/essentials-spring-examples` | **Moved in** from its standalone repository. Three Spring Boot apps modelling the *same* `shipping` domain deliberately, so the designs can be read against each other: `postgresql-cqrs` (EventStore + event-sourced `AggregateRoot` + projections), `postgresql-inbox-outbox` (JPA state-stored, strongly consistent reads), `mongodb-inbox-outbox` (Spring Data, same shape) |
| `examples/essentials-performance-lab` | Repeatable performance scenarios for EventStore/CDC, DurableQueues, Inbox/Outbox and subscriber topologies |

Building the examples against the working tree rather than a released version is the point: running the CDC
path, the snapshot and closing-books policies and the starters in real applications is what shook the new
subsystems out before release.

### 2.10 Build and test infrastructure

Integration tests were 62 minutes of Failsafe time across 136 classes, run strictly sequentially and multiplied
by five in CI. A full `mvn clean verify` now completes in **18 minutes** with 1673 tests passing and no module
skipped (measured: `postgresql-queue` 756s → 336s, `postgresql-event-store` 316s → 168s, `foundation` 44s →
19s).

What changed: `failsafe.forkCount` (default 2, overridable) replaced Surefire's self-cancelling
`parallel=classes` + `threadCount=1`; 67 of 97 IT files declared `@Container` on an *instance* field and so
started a database per test **method** — those are now static where the suite resets its own storage; container
images are pinned (`postgres:18.4`, `mongo:8.2`) through a new `EssentialsTestContainers` in `foundation-test`
rather than floating on `:latest`; the wal2json image gets a stable tag and `deleteOnExit=false` instead of
being rebuilt and orphaned per test method; and nine Awaitility waits of 2000–5000 seconds are capped at 60 so
a hang fails fast. `scripts/test-timings.sh` ranks test classes by elapsed time so this can be measured rather
than guessed at.

Sharing containers exposed pre-existing leaks that a fresh container per method had been hiding — twelve ITs
created a `HikariDataSource` per test method and never closed it — all fixed here.

Also new: `docs/ai-tooling.md`, per-module `CLAUDE.md` files, and consumer-facing `LLM/LLM-*.md` module
references.

---

## 3. Deprecations — removed in 0.60.0

> **Nothing was removed in 0.50.0.** Every constructor named below still exists, still compiles and still
> behaves exactly as it did. Upgrading and changing nothing gives you deprecation warnings and no errors.
> **The removals happen in 0.60.0.**

### 3.1 Why

Two API-shape problems had accumulated:

- **Wide constructors.** 84 constructors took six or more parameters; the worst took 17. Beyond about five
  arguments a call site cannot be read, and two adjacent parameters of the same type transpose silently.
- **`Optional` in constructors.** ~100 `Optional` parameters, of which `Optional<MeterRegistry>` alone
  accounted for 36 — every one immediately unwrapped to a nullable field or a default. The `Optional` bought
  nothing and cost every caller an `Optional.of(...)` wrapper.

The policy now: **`Optional` is for return types, builder-setter overloads and Spring `@Bean` signatures.**
Absence in a constructor is expressed as a neutral default, a sealed variant, or a builder-resolved nullable.
An ArchUnit guard (`EssentialsConstructionRules`) enforces it, with a committed freeze store so existing
violations cannot grow.

Design rationale: [`docs/constructor-ergonomics-and-optional-policy.md`](constructor-ergonomics-and-optional-policy.md).

### 3.2 The three shapes you will meet

**Shape 1 — `Optional<MeterRegistry>` → `MeasurementTaker`.** `MeasurementTaker` was always a fan-out that does
nothing when it has no recorders, so it — not the registry — is the right currency, and `MeasurementTaker.none()`
is the neutral default.

```java
// before
new RecordExecutionTimeDurableQueueInterceptor(Optional.of(meterRegistry), true, thresholds, "MyModule");
new RecordExecutionTimeDurableQueueInterceptor(Optional.empty(), false, thresholds, "MyModule");

// after
new RecordExecutionTimeDurableQueueInterceptor(MeasurementTaker.builder()
                                                              .setLoggingRecorder(MyClass.class, thresholds)
                                                              .setMeterRegistry(meterRegistry)
                                                              .build(),
                                               "MyModule");
new RecordExecutionTimeDurableQueueInterceptor(MeasurementTaker.none(), "MyModule");   // recording disabled
```

The separate `recordExecutionTimeEnabled` flag is gone: `MeasurementTaker.none()` disables recording, and the
interceptors branch on `MeasurementTaker.isRecording()` so a disabled interceptor still skips all context
assembly — the same hot-path behaviour the boolean gave you.

**Spring users: nothing to do.** The starters build these and are the only place `Optional<MeterRegistry>`
still appears, unwrapped on the spot in the `@Bean` method.

> Two classes deliberately kept the registry: `CdcAvailability` and `CdcSlotMetrics` register Micrometer
> `Gauge`s and `Counter`s, which a timing facade cannot express. They take a plain **nullable** `MeterRegistry`
> — pass `null` for "no metrics" instead of `Optional.empty()`.

**Shape 2 — wide constructor → builder or parameter object.**

```java
// before
new PostgresqlAggregateSnapshotRepository(eventStore, uowFactory, Optional.of("snapshots"), serializer,
                                          addStrategy, deleteStrategy, Optional.of(meterRegistry));

// after
PostgresqlAggregateSnapshotRepository.builder()
                                     .setEventStore(eventStore)
                                     .setUnitOfWorkFactory(uowFactory)
                                     .setSnapshotTableName("snapshots")   // or omit for the default
                                     .setJsonSerializer(serializer)
                                     .setAddNewSnapshotStrategy(addStrategy)
                                     .setSnapshotDeletionStrategy(deleteStrategy)
                                     .setMeterRegistry(meterRegistry)     // or omit for no metrics
                                     .build();
```

Every generated builder gives each previously-`Optional` argument **two** setters — a plain-value one and an
`Optional` overload — so code that already holds an `Optional` does not have to unwrap it.

**Shape 3 — enum + collaborator → sealed type.** `WalReplicationTailer` took a `CdcDeliveryMode` enum *and* an
`Optional<Consumer<List<PersistedEvent>>>`, then re-validated at runtime that the consumer was present when the
mode was `DIRECT`. Those are one value:

```java
// before — the illegal combination is expressible, and rejected at construction time
new WalReplicationTailer(dataSource, jdbi, uowFactory, slotName, inboxRepository, tailerProps,
                         pgSlotMode, cdcMode, CdcDeliveryMode.DIRECT, plugin,
                         Optional.of(onEvents), Optional.empty(), availability,
                         Optional.of(meterRegistry), Optional.empty());

// after — choosing DIRECT and supplying its consumer are the same act
new WalReplicationTailer(CdcTailerDependencies.builder()
                                              .setReplicationDataSource(dataSource)
                                              .setJdbi(jdbi)
                                              .setUnitOfWorkFactory(uowFactory)
                                              .setLogicalDecodingPlugin(plugin)
                                              .setAvailability(availability)
                                              .setMeterRegistry(meterRegistry)
                                              .build(),
                         CdcTailerSettings.of(slotName, tailerProps, pgSlotMode, cdcMode),
                         CdcDelivery.direct(onEvents));          // or CdcDelivery.inbox(inboxRepository)
```

The `directOnEvents cannot be null in DIRECT delivery mode` runtime check is gone, because the state it guarded
can no longer be constructed.

### 3.3 `components/foundation`

| Deprecated | Replacement |
|---|---|
| `RecordExecutionTimeCommandBusInterceptor(Optional<MeterRegistry>, boolean, LogThresholds, String)` | `(MeasurementTaker, String moduleTag)` |
| `RecordExecutionTimeDurableQueueInterceptor(…)` — same shape | `(MeasurementTaker, String moduleTag)` |
| `RecordExecutionTimeMessageHandlerInterceptor(…)` — same shape | `(MeasurementTaker, String moduleTag)` |
| `RecordSqlExecutionTimeLogger(…)` — same shape | `(MeasurementTaker, String moduleTag)` |
| `DBFencedLockManager(FencedLockStorage, UnitOfWorkFactory, Optional<String>, Duration, Duration, boolean, Optional<EventBus>)` | `(FencedLockStorage, UnitOfWorkFactory, FencedLockManagerSettings, EventBus)` — see `FencedLockManagerSettings.builder()` |
| `DefaultQueuedMessage(…)` — 11 args | `DefaultQueuedMessage.builder()` |
| `DefaultQueuedStatisticsMessage(…)` — 10 args | `DefaultQueuedStatisticsMessage.builder()` |
| `DefaultDurableQueueConsumer(ConsumeFromQueue, UOW_FACTORY, DURABLE_QUEUES, Consumer, long, QueuePollingOptimizer, List)` | `(ConsumeFromQueue, DurableQueueConsumerDependencies)` |
| `DurableLocalCommandBus` — 12 of 13 constructors | `DurableLocalCommandBus.builder()`; `DurableLocalCommandBus(DurableQueues)` stays for the all-defaults case |
| `RedeliveryPolicy(…)` — 7 args | `RedeliveryPolicy.builder()` / `exponentialBackoff()` / `linearBackoff()` / `fixedBackoff()` |
| `ConsumeFromQueue(…)` — 3 overloads | `ConsumeFromQueue.builder()` |
| `QueueMessage(…)` / `QueueMessages(…)` | `QueueMessage.builder()` / `QueueMessages.builder()` |
| `DBFencedLock(…)` — 6 args | `DBFencedLock.builder()` |

> **One behavioural nuance:** on `DBFencedLockManager` the `lockConfirmationInterval < lockTimeOut` check now
> fires when `FencedLockManagerSettings` is created, which is strictly earlier than before.

Also in `shared`: `PatternMatchingMethodInvoker(Object, MethodPatternMatcher, InvocationStrategy,
Optional<NoMatchingMethodsHandler>, Optional<InvocationTracker>)` → the same constructor with plain values
(`NoMatchingMethodsHandler.ignore()` / `InvocationTracker.noOp()`) or `PatternMatchingMethodInvoker.builder()`;
and `MeasurementTaker.Builder.withOptionalMicrometerMeasurementRecorder(Optional<MeterRegistry>)` →
`setMeterRegistry(…)`. New: `MeasurementTaker.none()`, `MeasurementTaker.isRecording()`,
`MeasurementTaker.Builder.setLoggingRecorder(Class<?>, LogThresholds)`,
`MeasurementInvocationTracker(MeasurementTaker)`.

### 3.4 `components/postgresql-event-store`

| Deprecated | Replacement |
|---|---|
| `AppendToStream(AggregateType, ID, Optional<Long>, List<?>)` | `(AggregateType, ID, Long, List<?>)` with `null`, or `AppendToStream.builder()` |
| `FetchStream(AggregateType, ID, LongRange, Optional<Tenant>)` | `(…, Tenant)` with `null`, or `FetchStream.builder()` |
| `LoadEventsByGlobalOrder(AggregateType, LongRange, List, Optional<Tenant>)` | `(…, Tenant)` with `null`, or `LoadEventsByGlobalOrder.builder()` |
| `RecordExecutionTimeEventStoreInterceptor(Optional<MeterRegistry>, boolean, LogThresholds, String)` | `(MeasurementTaker, String moduleTag)` |
| `MeasurementEventStoreSubscriptionObserver(…)` — same shape | `(MeasurementTaker, String moduleTag)` |
| `CdcAvailability(Optional<MeterRegistry>)` | `CdcAvailability(MeterRegistry)` with `null`, or `CdcAvailability()` |
| `CdcSlotMetrics(WalReplicationTailer, Optional<MeterRegistry>, String, CdcSlotProperties)` | `(WalReplicationTailer, MeterRegistry, String, CdcSlotProperties)` with `null` |
| `AbstractEventStoreSubscription(EventStore, AggregateType, SubscriberId, Optional<Tenant>, …)` — 7 args | `(EventStoreSubscriptionContext)` |
| `ExclusiveAsynchronousSubscription(…)` — 13 args | `(EventStoreSubscriptionContext, DurableSubscriptionContext, FencedLockManager, FencedLockAwareSubscriber, PersistedEventHandler)` |
| `NonExclusiveBatchedAsynchronousSubscription(…)` — 13 args | `(EventStoreSubscriptionContext, DurableSubscriptionContext, int, Duration, BatchedPersistedEventHandler)` |
| `NonExclusiveAsynchronousSubscription(…)` — 11 args | `(EventStoreSubscriptionContext, DurableSubscriptionContext, PersistedEventHandler)` |
| `ExclusiveInTransactionSubscription(…)` — 9 args | `(EventStoreSubscriptionContext, FencedLockManager, TransactionalPersistedEventHandler)` |
| `NonExclusiveInTransactionSubscription(…)` — 8 args | `(EventStoreSubscriptionContext, TransactionalPersistedEventHandler)` |
| `WalReplicationTailer(…)` — 15- and 17-arg forms | `(CdcTailerDependencies, CdcTailerSettings, CdcDelivery)` |
| `CdcDispatcher(…)` — 11 args | `(CdcDispatcherDependencies, CdcDispatcherSettings)` |
| `AggregateEventStreamConfiguration(…)` — 10 args | `AggregateEventStreamConfiguration.builder()` |
| `SeparateTablePerAggregateEventStreamConfiguration(…)` — 12 args | `SeparateTablePerAggregateEventStreamConfiguration.builder()` |
| `SeparateTablePerAggregateTypeEventStreamConfigurationFactory(…)` — 10 args | `…Factory.builder()` |
| `PostgresqlEventStore(EventStoreUnitOfWorkFactory, STRATEGY, Optional<EventStoreEventBus>, Function, EventStoreSubscriptionObserver)` | `PostgresqlEventStore.builder()` |
| `CdcEventStore(…)` — 6- and 7-arg forms | `CdcEventStore.builder()` |
| `CdcInboxRepository(…, Optional<MeterRegistry>[, String])` — both forms | `CdcInboxRepository.builder()`; the two non-`Optional` constructors are unchanged |
| `CdcEffectivenessMonitor(…)` — 6 args | `CdcEffectivenessMonitor.builder()` |
| `EventStoreEventBus(EventStoreUnitOfWorkFactory, int, int, OnErrorHandler, int, double)` | `EventStoreEventBus.builder()`; the shorter constructors are unchanged |
| `PersistedEventSubscriber(…)` — 6 args | `PersistedEventSubscriber.builder()` |
| `BatchedPersistedEventSubscriber(…)` — 7- and 8-arg forms | `BatchedPersistedEventSubscriber.builder()` |
| `DefaultEventStoreSubscriptionManager(…)` — 7- and 8-arg forms | `EventStoreSubscriptionManager.builder()` (also reachable as `DefaultEventStoreSubscriptionManager.builder()`) |
| `SeparateTablePerAggregateTypePersistenceStrategy(…)` — the two 6-arg forms | `SeparateTablePerAggregateTypePersistenceStrategy.builder()`; the 5-arg forms are unchanged |
| `EventStreamTableColumnNames(…)` — 12 args | `EventStreamTableColumnNames.builder()` |
| `PgReplicationSlots.SlotInfo(…)` — 15 args | `PgReplicationSlots.SlotInfo.builder()` |

> **Return types did not change.** `EventStoreSubscription.onlyIncludeEventsForTenant()` still returns
> `Optional<Tenant>` — only the field behind it became nullable. Same for
> `AppendToStream.getAppendEventsAfterEventOrder()`, `FetchStream.getTenant()` and
> `LoadEventsByGlobalOrder.getOnlyIncludeEventIfItBelongsToTenant()`.

### 3.5 Queues and fenced locks

| Deprecated | Replacement |
|---|---|
| `PostgresqlDurableQueues` — wide constructors | `PostgresqlDurableQueues.builder()` — **and see [§1.1](#11-️-silent-behaviour-changes--verify-your-configuration-read-this-first)** |
| `MongoDurableQueues` — all 10 constructors, including the `protected` one taking `TransactionalMode` | `MongoDurableQueues.builder()`, which gained `setTransactionalMode(…)` and `setMessageHandlingTimeout(…)` so the mode is reachable without it — **and see [§1.1](#11-️-silent-behaviour-changes--verify-your-configuration-read-this-first)** |
| `MongoDurableQueues.DurableQueuedMessage(…)` — 16 args | `MongoDurableQueues.DurableQueuedMessage.builder()`; the no-arg constructor Spring Data uses is unchanged |
| `PostgresqlDurableQueueConsumer(…)` / `MongoDurableQueueConsumer(…)` — 7 args | `(ConsumeFromQueue, DurableQueueConsumerDependencies)` |
| `PostgresqlFencedLockManager` / `MongoFencedLockManager` — `Optional`-taking constructors | their existing `builder()` |
| `LocalEventBus(…)` — wide constructor | `LocalEventBus.builder()` |

### 3.6 Everything else

The remaining modules follow the same pattern; the full per-class tables are in
[`docs/MIGRATION-NEXT_MAJOR.md`](MIGRATION-NEXT_MAJOR.md).

- **`components/eventsourced-aggregates`** — all of these gain a `builder()`, with their `Optional`-taking and
  wide constructors deprecated: `AsyncAggregateSnapshotRepository`, `DurableAsyncAggregateSnapshotRepository`,
  `PostgresqlAggregateSnapshotRepository`, `PostgresqlAggregateSnapshotStore`,
  `PostgresqlAggregateSnapshotJobRepository`, `PostgresqlAggregateSnapshotJobProcessor`,
  `BuiltInClosingBooksPolicyEvaluator`, `ClosingBooksCoordinator`, `ClosingBooksManager`,
  `DefaultClosingBooksScheduledScanProcessor`, `PostgresqlClosingBooksGenerationRepository`,
  `DefaultAggregateGenerationArchiver`, `PostgresqlAggregateArchiveRegistry`, `DefaultAggregateLifecycleApi`,
  `DefaultAggregateLifecycleStatisticsApi`. `StatefulAggregateRepository.DefaultStatefulAggregateRepository`'s
  protected constructors are deprecated — use `StatefulAggregateRepository.builder(eventStore)` or the
  `from(...)` factories.
- **Starters and admin API** — `DefaultAggregateSnapshotRepositoryFactory` (9 args), `DefaultEventStoreApi`,
  `DefaultCdcApi`, `DefaultAggregateLifecycleConfigurationValidator` (7 args) and `CdcHealthIndicator` all gain
  a `builder()`.

### 3.7 Deliberately not deprecated

- **Records are exempt.** A record's canonical constructor is not subject to either rule: its parameter list
  *is* its component list, and a component's type *is* its accessor's return type — and `Optional` return types
  are explicitly permitted. `SnapshotTriggerContext`, `AggregateGeneration`,
  `AggregateSnapshotPolicyDescriptor` and `AggregateClosingBooksPolicyDescriptor` keep their `Optional`
  components, unchanged.
- **`PersistedEvent.DefaultPersistedEvent` and `PersistableEvent.DefaultPersistableEvent` keep their wide
  constructors** and are the only two classes in the sweep not even marked `@Deprecated(forRemoval = true)`.
  Under Jackson 3 a constructor parameter *name* is part of the JSON contract, so reshaping the creator of a
  persisted type risks the wire format. `@Deprecated(forRemoval = true)` is a promise to remove at the next
  major, and here it is a promise the persisted format does not let us keep.

### 3.8 Finding your call sites

```bash
mvn -Dmaven.compiler.showDeprecation=true clean compile
```

Every affected constructor carries `@Deprecated(forRemoval = true)`, so `-Xlint:removal` (on by default for
`forRemoval`) names each one with its replacement in the javadoc `@deprecated` tag. Fixing them is mechanical
and can be done incrementally across the 0.50.x line.

---

## 4. What 0.60.0 will break

0.60.0 is a major release and will break compilation for anyone who has not acted on this section. Three things
are **committed** — §4.1 to §4.3. A fourth, §4.4, is **signalled but not committed**, and is called out
separately so it is not read as a promise.

### 4.1 Java 25 becomes the baseline

`--release 25`, so class files carry major version 69 and **a Java 21 runtime will reject them with
`UnsupportedClassVersionError`** — the same hard floor 0.50.0 raised to 21. 0.50.0 already builds and tests on
JDK 25 in CI (`maven-enforcer` pins `[21,26)`), so the toolchain is proven; what changes in 0.60.0 is the
*runtime* requirement.

**Do now:** move your production runtime to a Java 25 JDK while still on 0.50.0. Because 0.50.0 targets 21, it
runs unmodified on 25 — so the runtime hop and the library hop can be taken separately rather than together.

### 4.2 Spring Boot 4.1

The starters will move to the 4.1 line. Within a Spring Boot minor the relocation churn is far smaller than the
3.3 → 4.0 jump documented in [§1.3](#13-spring-boot-40x), but properties deprecated at level `error` in 4.0
stop being bound entirely, so clear your deprecation warnings on 4.0.x rather than carrying them forward.

**Do now:** run your application on 4.0.x with `spring-boot-properties-migrator` on the classpath (test scope)
and fix everything it reports.

### 4.3 Every `@Deprecated(forRemoval = true)` constructor is removed

Everything in [§3](#3-deprecations--removed-in-0600) — 137 members carry `@Deprecated(forRemoval = true)`
across `shared`, `foundation`, `postgresql-event-store`, `eventsourced-aggregates`, the queue and fenced-lock
modules, and the starters. There is **no** compatibility shim and no deprecated-but-retained tier: the removal
is what the annotation promised.

The two exceptions named in [§3.7](#37-deliberately-not-deprecated) — `DefaultPersistedEvent` and
`DefaultPersistableEvent` — are not annotated and therefore not part of this.

**Do now:** compile with deprecation warnings visible and migrate call sites to the builders as you touch them.
Every replacement already exists in 0.50.0, so this work can be completed entirely before 0.60.0 ships and
carries no flag-day.

### 4.4 The Jackson 2 flavour is deprecated — removal after 0.60.0

> **Planned, not committed.** Unlike §4.1–§4.3, no removal release is fixed yet. `types-jackson` and
> `immutable-jackson` remain fully supported in 0.60.0; this is advance notice so the migration can be planned
> rather than absorbed.

The Jackson 2 flavour (`types-jackson` / `immutable-jackson`, group `com.fasterxml.jackson.core`) is deprecated
as of 0.60.0. Jackson 3 (`types-jackson3` / `immutable-jackson3`, group `tools.jackson.core`) has been the
default since 0.50.0 and will become the only flavour.

**Why not in 0.60.0 itself.** Two reasons. Spring Boot has *not* removed its own Jackson 2 support — the
`spring-boot-jackson2` module still ships in the 4.1 line, deprecated, with removal stated only as "a future
4.x release". Consumers on the Essentials Jackson 2 flavour are usually there because some *other* dependency
still needs Jackson 2, which is exactly the population that module exists to serve; removing ahead of upstream
would strand them. And 0.60.0 already carries a runtime floor change, a Spring Boot minor and 137 removals —
the Jackson 3 migration has two failure modes that are silent on write and only surface on read-back
([§1.4](#14-jackson-3-is-the-default-flavour)), which is not a thing to stack onto that.

**The likely trigger is upstream, not a date:** removal is expected to follow Spring Boot dropping
`spring-boot-jackson2`, or a later Essentials major — whichever comes first.

**Do now, if you are still on the Jackson 2 flavour:** work through the two payload-class checks in
[§1.4](#14-jackson-3-is-the-default-flavour) — constructor parameter names, and `@JsonDeserialize(keyUsing = …)`
— then drop the exclusion and let the Jackson 3 flavour arrive transitively. Both majors write byte-identical
JSON, so no persisted data has to be rewritten and the move can be made and reverted freely.

> **Your persisted data stays readable after the flavour is gone.** `EssentialsObjectMappersWireFormatTest`
> and its golden document are deliberately independent of the build profile and will outlive it — they are what
> guarantees that events written by a Jackson-2-era deployment still deserialize under Jackson 3.

### 4.5 Recommended upgrade order

1. Upgrade to **0.50.0** on Java 21 / Spring Boot 4.0.x. Apply [§1](#1-breaking--what-you-must-apply), starting
   with the silent behaviour changes.
2. Clear all Essentials deprecation warnings ([§3](#3-deprecations--removed-in-0600)) — mechanical, and safe to
   do incrementally.
3. Clear all Spring Boot 4.0 deprecation warnings.
4. Move the runtime to **Java 25** while still on 0.50.0.
5. Upgrade to **0.60.0**. If steps 2–4 are done, this should be a version bump.
6. *If still on the Jackson 2 flavour:* move to Jackson 3 ([§4.4](#44-the-jackson-2-flavour-is-deprecated--removal-after-0600)).
   Deliberately last and deliberately separate — it is not required for 0.60.0, and doing it on its own release
   means a read-back failure has only one possible cause.

---

## 5. Module inventory

**Added**

| Module | Notes |
|---|---|
| `types-jackson3` | Jackson 3 flavour of `types-jackson`; same FQCNs |
| `immutable-jackson3` | Jackson 3 flavour of `immutable-jackson`; same FQCNs |
| `components/admin-api-spec` | The OpenAPI contract + its build gates |
| `components/spring-boot-starter-admin-api` | Serves the contract over Spring WebMVC |
| `components/admin-api-client-java` | Generated Java client |
| `examples/essentials-trading-demo` | New demo — not released |
| `examples/essentials-spring-examples` | Moved in from a standalone repo — not released |
| `examples/essentials-performance-lab` | Not released |

**Removed**

| Module | Notes |
|---|---|
| `components/vaadin-ui` | No replacement in kind — see [§1.5](#15-the-vaadin-admin-ui-is-gone) |

**Replaced in place**

| Module | Notes |
|---|---|
| `components/spring-boot-starter-admin-ui` | Same artifactId, entirely new implementation: Thymeleaf + vanilla JS instead of Vaadin |

35 modules in the reactor, up from 28 — 32 published plus the three examples. `components/foundation-test` remains an internal test utility, not a
consumer API; the `examples/` modules are excluded from the release.

---

## 6. Reference

| Document | What it covers |
|---|---|
| [`docs/MIGRATION-NEXT_MAJOR.md`](MIGRATION-NEXT_MAJOR.md) | The complete per-module deprecated → replacement tables |
| [`docs/constructor-ergonomics-and-optional-policy.md`](constructor-ergonomics-and-optional-policy.md) | Why the sweep was done and what the ArchUnit guard enforces |
| [`docs/cdc.md`](cdc.md) | CDC design and operations — prerequisites, plugins, slots, poison handling |
| [`docs/cdc-improvements.md`](cdc-improvements.md) | CDC hardening work in detail |
| [`docs/subscription-improvements.md`](subscription-improvements.md) | Subscription statistics and observer work |
| [`docs/openapi/README.md`](openapi/README.md) | The admin API contract, its gates and its changelog |
| [`docs/ai-tooling.md`](ai-tooling.md) | AI tooling conventions used in this repository |
| [`LLM/LLM.md`](../LLM/LLM.md) | Entry point for the per-module consumer references |
| [`README.md`](../README.md) | Version compatibility table and the Jackson flavour selection guide |

**Standing constraints, unchanged in 0.50.0** — worth restating because they are the ones people forget:

- All third-party integrations are `provided` scope and therefore **not transitive**. Consumers declare their
  own Jackson, Spring, JDBI, Mongo and Micrometer dependencies.
- FencedLock, DurableQueues, Inbox and Outbox coordinate **multiple instances of one service**. They are not
  cross-service infrastructure.
- Table and collection names are string-concatenated into queries. Validate them via
  `PostgresqlUtil.checkIsValidTableOrColumnName()` / `MongoUtil.checkIsValidCollectionName()`, and prefer
  hardcoded names.
- `EventOrder` is per-stream; `GlobalEventOrder` is across all streams of an `AggregateType`. Ordering is never
  by timestamp.
