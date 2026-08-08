# postgresql-event-store

Append-only PostgreSQL event store with durable subscriptions, gap handling, and CDC via WAL logical replication. Maven: `postgresql-event-store`.

## Package Structure

All under `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`:

| Package | Contents |
|---|---|
| (root) | `EventStore` interface, `PostgresqlEventStore` impl, `ConfigurableEventStore`, polling optimizers |
| `eventstream` | `PersistedEvent`, `AggregateEventStream`, column type enums, `EventStreamTableColumnNames` |
| `persistence` | `AggregateEventStreamPersistenceStrategy` SPI, `AggregateEventStreamConfiguration` |
| `persistence.table_per_aggregate_type` | `SeparateTablePerAggregateTypePersistenceStrategy` — one table per aggregate type |
| `persistence.jdbi` | JDBI row mappers and SQL helpers |
| `serializer.json` | `JSONEventSerializer` SPI, `JacksonJSONEventSerializer` (Jackson 2), `Jackson3JSONEventSerializer` (Jackson 3), `EssentialsJSONEventSerializers` factory, `EventJSON` value type |
| `serializer` | `AggregateIdSerializer` SPI |
| `transaction` | `EventStoreUnitOfWork`, `EventStoreUnitOfWorkFactory`, `EventStoreManagedUnitOfWorkFactory` |
| `subscription` | `EventStoreSubscriptionManager`, `DefaultEventStoreSubscriptionManager`, subscription impls, `DurableSubscriptionRepository` |
| `subscription.notify` | NOTIFY-aware polling optimizer (`NotifyAwareEventStorePollingOptimizer`, `NotifyTriggerInstaller`) |
| `gap` | `EventStreamGapHandler` SPI, `PostgresqlEventStreamGapHandler`, `NoEventStreamGapHandler` |
| `interceptor` | `EventStoreInterceptor` SPI + chain, flush-and-publish interceptor, Micrometer interceptors |
| `observability` | `EventStoreSubscriptionObserver` SPI, Micrometer impl |
| `operations` | Operation value objects: `AppendToStream`, `FetchStream`, `LoadEvent`, `LoadEvents`, `LoadEventsByGlobalOrder`, etc. |
| `processor` | `EventProcessor` / `ViewEventProcessor` — pattern-matching reactive event consumers |
| `bus` | `EventStoreEventBus`, `PersistedEvents` commit lifecycle |
| `cdc` | CDC subsystem — see below |
| `cdc.converter` | WAL payload → `PersistedEvent` converters; `LogicalDecodingPlugin` impls |
| `cdc.filter` | `WalMessageFilter` SPI, regex filter, pgoutput raw filter |
| `cdc.handler` | `WalReplicationTailerErrorHandler` SPI |
| `api` | REST-style status/config DTOs and `CdcApi`/`EventStoreApi` facades |

## Key Classes

| Class | Role |
|---|---|
| `PostgresqlEventStore` | Core impl; composes persistence strategy, interceptors, subscriptions, in-memory projectors |
| `ConfigurableEventStore` | Extension of `EventStore` exposing configuration mutators (add interceptor, stream config, projector) |
| `CdcEventStore` | Decorator over `EventStore`; adds backfill→live handover, CDC-aware polling |
| `WalReplicationTailer` | Connects PG replication slot, streams WAL bytes, hands raw payload to `LogicalDecodingPlugin` |
| `CdcDispatcher` | Polls `CdcInboxRepository`, decodes via `LogicalDecodingPlugin`, dispatches `PersistedEvent` lists |
| `LogicalDecodingPlugin` | SPI — owns slot options, usability check, payload decoding, gap extraction; impls: `PgOutputLogicalDecodingPlugin`, `Wal2JsonLogicalDecodingPlugin` |
| `CdcInboxRepository` | Staging table (`eventstore_cdc_inbox`) between tailer write and dispatcher read |
| `CdcEventBus` | In-memory fan-out bus between `CdcDispatcher` → `CdcEventStore` subscribers |
| `SeparateTablePerAggregateTypePersistenceStrategy` | Only shipping persistence strategy; one PG table per `AggregateType` with `global_event_order` sequence |
| `AggregateEventStreamPersistenceStrategy` | SPI for alternative persistence layouts |
| `EventStoreSubscriptionManager` | Manages durable resume-point subscriptions; delegates exclusive subscriptions to `PostgresqlFencedLockManager` |
| `DefaultEventStoreSubscriptionManager` | Concrete impl; polling loop using `EventStorePollingOptimizer` |
| `DurableSubscriptionRepository` | Persists per-subscriber resume `GlobalEventOrder` |
| `EventStreamGapHandler` | SPI tracking transient/permanent `GlobalEventOrder` gaps per subscriber |
| `PostgresqlEventStreamGapHandler` | DB-backed gap tracker; promotes transient→permanent after tx-timeout window |
| `EventStoreInterceptor` | SPI: around-advice for append, load, fetch, poll operations |
| `EventStorePollingOptimizer` | SPI: controls inter-poll sleep; impls: `SimpleEventStorePollingOptimizer`, `JitteredEventStorePollingOptimizer`, `NotifyAwareEventStorePollingOptimizer` |
| `NotifyTriggerInstaller` | Functional interface; persistence strategy calls back to install `pg_notify` trigger per table |
| `InMemoryProjector` | SPI for in-memory aggregate rehydration |
| `EventStoreUnitOfWork` | UoW carrying accumulated `PersistedEvent` list; fires `PersistedEventsCommitLifecycleCallback` on commit |

## Test Structure

- **ITs** (`*IT.java`) — Testcontainers-based; require Docker. Use `PostgreSQLContainer` (standard) or `GenericContainer` built from `src/test/resources/docker/postgresql-wal2json/Dockerfile` (PG 17 + wal2json plugin) for CDC tests.
- **Abstract base** — `AbstractLogicalReplicationPostgresIT` wires JDBI, replication datasource, UoW factory for all CDC ITs.
- **Unit tests** — no Docker; test individual classes (optimizer, decoder, column name builder, etc.).
- **Multi-node** — `*_2_node_*IT` tests run two `DefaultEventStoreSubscriptionManager` instances sharing one DB to verify exclusive subscription handover via fenced lock.
- **CDC parity** — `CdcEventStoreSubscriptionParity_IT` asserts CDC and polling subscriptions observe identical event sequences.
- **Test data** — `test_data/` contains `OrderEvent`, `ProductEvent`, `OrderId`, `ProductId`, `CustomerId` used across ITs.

## Extension Points

| SPI | Purpose |
|---|---|
| `AggregateEventStreamPersistenceStrategy` | Alternative DB layout (e.g. single-table) |
| `EventStoreInterceptor` | Around-advice on append/load/fetch/poll |
| `EventStorePollingOptimizer` | Custom inter-poll delay strategy |
| `EventStreamGapHandler` | Custom gap detection/promotion logic |
| `InMemoryProjector` | Custom aggregate rehydration |
| `JSONEventSerializer` | Swap Jackson for another serializer |
| `AggregateIdSerializer` | Custom aggregate ID serialization |
| `LogicalDecodingPlugin` | New WAL decoding plugin (beyond pgoutput/wal2json) |
| `WalMessageFilter` | Pre-decode WAL payload filtering |
| `WalReplicationTailerErrorHandler` | Custom replication error recovery |
| `EventStoreSubscriptionObserver` | Subscription lifecycle observability hook |
| `NotifyTriggerInstaller` | Invoked per new event-stream table when NOTIFY polling enabled |
| `DurableSubscriptionRepository` | Custom storage for subscriber resume points |

## Jackson Flavors

Both Jackson majors are supported; a build selects one via `essentials.types-jackson.artifactId` (default Jackson 3, `-Pjackson2` for Jackson 2).

- Get a serializer from `EssentialsJSONEventSerializers.createForActiveJacksonFlavor()` — never `new JacksonJSONEventSerializer(...)`/`new Jackson3JSONEventSerializer(...)` with a hand-built mapper. The two write identical JSON only when their mappers come from `EssentialsObjectMappers`.
- `EssentialsObjectMappersWireFormatTest` is the compatibility gate: it asserts the persisted format against a committed golden document, and runs under **both** profiles. That is what proves a Jackson 3 deployment reads payloads Jackson 2 wrote.
- Run the suite both ways when touching serialization: `mvn test` (Jackson 3) and `mvn -Pjackson2 test`.
- `ActiveJacksonFlavorTest` guards the credibility of that: a test hard-coding `new JacksonJSONEventSerializer(new ObjectMapper())` passes under the Jackson 3 flavor while exercising only Jackson 2 — green and meaningless. It derives the expected flavor from the classpath independently. Build CDC/serializer test fixtures with `EssentialsJSONEventSerializers.createForActiveJacksonFlavor()`.
- **CDC runs on both majors.** The WAL converters/extractor parse via `JSONEventSerializer.deserialize(..., Object.class)` into plain maps and lists, so one implementation serves both — the injected serializer decides. They take the interface, never the concrete `JacksonJSONEventSerializer`.
- **The WAL pre-filter is the one deliberate duplication**: `DefaultWalMessageFilter` (Jackson 2) and `Jackson3WalMessageFilter` (Jackson 3) scan tokens without materializing the payload, and the streaming APIs differ. Get one via `WalMessageFilters.createForActiveJacksonFlavor(...)`. `WalMessageFilterFlavorParityTest` asserts the pair agree on every payload, so they cannot drift.
- **`canonicalJson` fidelity**: `PgOutputToPersistedEventConverter` re-serializes the payload it persists, through untyped binding. `EssentialsObjectMappers` enables `USE_BIG_DECIMAL_FOR_FLOATS` on both majors for exactly this reason — without it `1.10` would be rewritten as `1.1` in persisted events.

## Gotchas

- **Global event order is per-table sequence**, not cross-table. Subscribers to different `AggregateType`s have independent `GlobalEventOrder` spaces.
- **Table names go straight into SQL** via string concat. `PostgresqlUtil.checkIsValidTableOrColumnName` is first-line defense only — sanitize all external inputs before they reach `SeparateTablePerAggregateEventStreamConfiguration`.
- **CDC delivery modes**: `INBOX` (tailer writes to staging table, dispatcher polls) vs `DIRECT` (tailer calls consumer inline). `INBOX` survives tailer restarts; `DIRECT` logs a warning about re-delivery risk.
- **CDC is opt-in** — `CdcProperties.enabled` defaults to `false`, and the starter gates every CDC bean on `essentials.eventstore.cdc.enabled=true`. Enabling it also needs `wal_level=logical`, slot/wal-sender headroom, a `REPLICATION` role, and (for pgoutput) a publication covering the event-stream tables. Full checklist: `docs/cdc.md` §1.1
- **`CdcMode.AUTO` vs `REQUIRE`**: `AUTO` silently falls back to polling if WAL logical replication is unavailable; `REQUIRE` fails startup. The `CdcEffectivenessMonitor` tracks whether CDC is actually delivering. Note `AUTO` makes a half-configured CDC setup look fine — the app boots and stays correct, just on polling.
- **A poll on the inactive path during startup is a *warm-up*, not a fallback.** `CdcAvailability` starts `INACTIVE` and only goes `ACTIVE` once the tailer has connected and taken the slot, but the lifecycle starts subscriptions **before** `walReplicationTailer` — so every subscription that comes up during boot legitimately begins on polling and is switched to the CDC bus by `buildAdaptiveLiveSource`'s `stateChanges()` subscription moments later. `CdcAvailability.fallbackUsed()` therefore routes on `everActive`: before the first `active(slot)` it increments `warmupPollCount` / `essentials.cdc.warmup_poll_total`, afterwards `fallbackCount` / `essentials.cdc.fallback_total`. Counting warm-up as fallback is what previously made a healthy boot report "CDC has fallen back to polling N times" with an empty `reason` and no error, N varying run to run with a fenced-lock race. Don't "simplify" the two counters back into one — `fallback_total` is the alertable signal and must stay free of startup noise
- **`essentials.cdc.eventstore.fallback.poll.count` still includes warm-up polls** — it is incremented in `CdcEventStore.pollEvents` next to `availability.fallbackUsed()` and counts every trip down the polling branch, unlike `fallback_total`. Deliberate: it measures the polling branch, not CDC health. Read it together with `essentials.cdc.active`, never on its own
- **`everActive` distinguishes "healthy" from "CDC never came up"** — both report `fallbackCount = 0`. A non-zero `warmupPollCount` with `everActive = false` means nothing was ever delivered over CDC
- **pgoutput publication coverage**: `WalReplicationTailer.onStreamStarted()` checks that every event-stream table is in the configured publication. Missing tables → CDC runs silently without events. Most common operator mistake.
- **CDC must reproduce the polling path's `PersistedEvent`, field for field — including types.** `PersistedEventRowMapper` deserializes `aggregate_id` via the configured `AggregateIdSerializer`, so `PersistedEvent.aggregateId()` holds the *typed* id. The WAL only carries text, so CDC converters must do the same via `AggregateIdSerializerResolver`; skipping it hands subscribers a raw `String` under CDC and a typed id under polling. That broke `EventProcessor.forwardEventToInbox` for every forwarded event (`Expected java.lang.String to be an instance of …Id`), silently starving projections while CDC itself reported zero failures. Payload parity is gated by `WalReplicationWithEssentialsAggregatePgOutputIT#cdc_delivered_events_are_field_for_field_identical_to_polled_events`, which diffs CDC-delivered events against `loadEventsByGlobalOrder` including the aggregate-id's runtime class. Don't mistake `CdcEventStoreSubscriptionParity_IT` for that gate — it runs against an INACTIVE `CdcAvailability`, so it exercises fallback-to-polling and never touches the converter. Payload JSON is compared *parsed*, not textually: the polling path returns Postgres's `jsonb` rendering while CDC returns the converter's canonical compact form, and that re-serialization is deliberate
- **The CDC inbox `lsn` column is a dedup key, not a WAL coordinate** — it comes from `LogicalDecodingPlugin.inboxDedupKey(payload, lsn)`, which defaults to the raw LSN. pgoutput must override it: the streaming protocol reports **every** RELATION (`'R'`) message at `0/0`, so a raw-LSN key lets `unique(slot_name, lsn)` + `on conflict do nothing` collapse all tables' schema onto one row — the first table works, every other one quarantines its every insert with `MissingRelationMetadataException`. Single-table tests cannot see this; CDC tests need ≥2 event-stream tables
- **The decoder's relation cache is in-memory, its source is the inbox** — RELATION messages stream once per replication session, so a restarted dispatcher starts blind. `CdcDispatcher` primes from retained `'R'` rows on start and re-primes once per tick on a cache miss; those rows are exempt from `DispatchedRowPolicy.DELETE` so the source survives
- **Replication slot name** computed by `DefaultCdcSlotNameProvider`; slot persists on PG side — deleting/recreating mid-stream causes gap. `PgReplicationSlots.forceRecreate` exists for recovery but loses all unread WAL.
- **Gap promotion window**: transient gaps become permanent after the DB transaction timeout elapses. Changing PG `statement_timeout` affects gap behavior. Use `PostgresqlEventStreamGapHandler` (not `NoEventStreamGapHandler`) in production.
- **Exclusive subscriptions use `PostgresqlFencedLockManager`**. If lock TTL is shorter than subscription resume time, ownership flaps. Size lock TTL accordingly.
- **`EventStoreUnitOfWork` accumulates events in-memory** then fires callbacks at commit. Interceptors touching accumulated events (e.g. `FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream`) must handle re-entrant appends carefully.
- **Warm-up subscribers** (backfill phase) must not stay pinned to polling mode after catching up — `CdcEventStore` tracks per-subscriber phase transitions explicitly.
- **Test code is flavor-sensitive too** — a test that builds a Jackson 2 mapper and registers `EssentialTypesJacksonModule` will not compile under the Jackson 3 flavor (same FQCN, different Jackson major). Use the flavor-neutral factories.
- **Multi-tenancy**: tenant filtering happens at query time via optional `Tenant` param on all load/poll ops. No row-level security — tenant isolation is application-layer only.
