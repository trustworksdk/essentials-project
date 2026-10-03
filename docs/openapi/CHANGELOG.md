# Essentials Admin API — Changelog

All notable changes to the Essentials Admin API contract
(`components/admin-api-spec/openapi/essentials-admin-api.yaml`) are documented here.

The contract follows semantic versioning, with the major aligned to the path prefix
(`/api/essentials/admin/v{major}`). Additive changes are released as minor versions within the same
major; breaking changes are introduced under a new major path served side-by-side.

## v1.0.0 — unreleased

Initial contract, generated code-first from the seven Essentials admin SPI interfaces:

- `DBFencedLockApi` → `fenced-locks`
- `SchedulerApi` → `scheduler`
- `PostgresqlQueryStatisticsApi` → `postgresql-query-statistics`
- `DurableQueuesApi` → `durable-queues`
- `EventStoreApi` → `event-store`
- `CdcApi` → `cdc`
- `PostgresqlEventStoreStatisticsApi` → `event-store-statistics`

40 operations across 38 paths, served under `/api/essentials/admin/v1`. Authorization is role-based
and surfaced per operation via the `x-required-roles` vendor extension; the contract is
transport-agnostic.

Contract conventions settled before release:

- **Server-relative paths.** The `/api/essentials/admin/v1` prefix lives only in `servers[0].url`.
  It was previously repeated in every path key, which made both generated clients prepend it twice.
- **Complete error statuses.** `401`, `403` and `500` on every operation, `400` wherever there is a
  parameter or request body to reject, `404` on the `Optional`-returning operations. `Error.status`
  and `Error.error` are required.
- **No security scheme at all.** The contract states which roles satisfy an operation
  (`x-required-roles`) and nothing about how a caller is authenticated. A `bearerAuth` scheme was
  briefly declared as a client convenience and then removed: the contract should not imply a
  mechanism the adapter does not implement. Authentication is reported by the application's
  `EssentialsAuthenticatedUser`, authorization decided by its `EssentialsSecurityProvider`.
- **Conservative `required`.** Primitive-typed properties plus verified always-present reference
  properties are required; the rest stay optional. Properties that are null by design carry
  `nullable: true` and the reason as their description.

PostgreSQL statistics, added later in the same unreleased version:

- **`PostgresqlTableStatisticsApi` → `postgresql-table-statistics`.** `GET /postgresql/table-statistics`
  reports size, activity, dead rows, cache hit and last vacuum/analyze for every table the Essentials
  components own, each tagged with a section. The three `event-store-statistics` operations cover only
  the event-stream tables; they remain for existing clients. Each table also carries `rowsHotUpdated` and
  its `indexes` (`ApiIndexStatistics`: size, scans, entries read, rows fetched, cache hit, and whether the
  index is unique, primary or valid), for spotting unused indexes and updates that cannot be HOT.
- **`GET /postgresql/query-statistics/slowest`** ranks `pg_stat_statements` by `orderBy`
  (`TOTAL_TIME`, `MEAN_TIME`, `MAX_TIME`, `CALLS`, `BLOCKS_READ`) with a `limit` of 1-100, for the current
  database only. `ApiQueryStatistics` gains `rows`, `minTime`, `maxTime`, `stddevTime`,
  `sharedBlksHit`, `sharedBlksRead` and a nullable `cacheHitRatio`.
- **`ApiTableCacheHitRatio.cacheHitRatio` is now a whole percentage.** The server read the 0-1 ratio as
  an integer, so every value below an exact 100% came back as `0`. Tables with no block access yet are
  left out rather than reported as `0`.

Scheduler, added later in the same unreleased version:

- **`POST /scheduler/jobs/{jobName}/run`** (`SchedulerApi.runJobNow`) runs a job registered with the
  application's scheduler once and returns `ApiScheduledJobRun` (job type, start, duration, succeeded,
  error). It requires the new `essentials_scheduler_writer` role. `404` for a name the scheduler did not
  register - another application's `cron.job` row cannot be run; `409` for an executor job when the
  request reached an instance not holding the scheduler lock. This is the first operation declaring
  `409`.

Event causation, added later in the same unreleased version (`docs/event-causation.md`):

- **`GET /event-store/aggregate-types/{aggregateType}/aggregates/{aggregateId}/events`**
  (`EventStoreApi.findAggregateEvents`) lists an aggregate's most recent events (`limit`, default 100, at most
  1000), oldest first - the place to start, since a user knows the business id rather than an event id.
- **`GET /event-store/events/{eventId}`** (`EventStoreApi.findEvent`) finds an event by its id alone,
  in whichever registered aggregate type's event stream holds it. `404` when none does.
- **`GET /event-store/events/{eventId}/causation-chain`** (`EventStoreApi.findCausationChain`) answers
  "why did this happen?": the event, then its recorded cause, then that event's cause, up to
  `maxDepth` (default 20, at most 100) events.
- **`GET /event-store/events/{eventId}/caused-events`** (`EventStoreApi.findEventsCausedBy`) answers
  "what did this cause?": the event's direct effects across all registered aggregate types. `409` when
  the opt-in caused-by-event-id index (`essentials.eventstore.causation.index-enabled`) is not enabled.

All four return the new `ApiCausationEvent` - the event's identity, position, timestamp and
`causedByEventId`, deliberately without event or metadata payloads - and require
`essentials_subscription_reader` or `essentials_admin`.
