# Migration guide — 0.61

0.61 removes no API and leaves the platform as it was in 0.60 (Java 25, Spring Boot 4.1, Jackson 3). Two changes
need an action from some applications, and seven defaults or behaviours change without a compile error. This guide
lists what to do, in the order to do it; the full description of each change is in
[RELEASE-NOTES-0.61.0.md](./RELEASE-NOTES-0.61.0.md).

---

## Shard-owned adapter: stop every 0.60.x instance first

Applies only when the shard-owned `DurableQueues` adapter is on (`essentials.shard-owned-queue.durable-queues-enabled=true`).

0.61 writes queue messages in a new format (format 2) that 0.60.x cannot read. A 0.60.x instance that receives a
format 2 message fails its delivery and, after the retries, dead-letters it. 0.61 still reads format 1 rows, queued or
dead-lettered, so nothing needs migrating.

**What to do:** stop every 0.60.x instance that uses the same shard-owned queues, then start 0.61. Do not run a rolling
upgrade across these two versions. A message 0.60.x already dead-lettered this way can be resurrected once only 0.61
runs. Details: [RELEASE-NOTES-0.61.0.md § 1](./RELEASE-NOTES-0.61.0.md#1-breaking--what-you-must-apply).

---

## Schema validate mode: apply the gap-table index drop

Applies only with `essentials.schema.mode=validate` (or a hand-built component given a validating `SchemaOwnership`).

`PostgresqlEventStreamGapHandler` drops the redundant index `transient_subscriber_gaps_index` through a new schema
change, `transient-gaps-index-dropped`. Under `validate` the first start of 0.61 fails until that change is applied,
like any new schema change.

**What to do:** run 0.61 once with `essentials.schema.mode=emit`, apply the script it writes (the `DROP INDEX IF EXISTS`
and its ledger row) with a user that has DDL rights, then start. In the default `create` mode nothing needs doing. A
0.60.x instance restarting during the upgrade creates the index again; the next start of 0.61 drops it.

---

## Silent behaviour changes to check

Nothing here fails at startup or compile time. Check each one against your application; the release notes say what
to set to keep the old behaviour.

| Change | Keep the old behaviour with | Notes |
|---|---|---|
| CDC hands out event JSON as stored, with PostgreSQL's `jsonb` spacing | - (compare parsed JSON, not text) | [§ 1.1.1](./RELEASE-NOTES-0.61.0.md#111-cdc-hands-out-event-json-as-stored-postgresql-event-store) |
| Snapshot and closing-books policies on declared aggregates now apply to repositories built at startup | change the annotation | [§ 1.1.2](./RELEASE-NOTES-0.61.0.md#112-policies-on-declared-aggregates-now-apply-to-repositories-built-at-startup-spring-boot-starter-postgresql-event-store) |
| Aggregates without a snapshot policy are snapshotted every 1000 events, not 10 | `essentials.eventstore.snapshots.default-every-n-events=10` | [§ 1.1.3](./RELEASE-NOTES-0.61.0.md#113-aggregates-without-a-snapshot-policy-are-snapshotted-every-1000-events-spring-boot-starter-postgresql-event-store) |
| `AnnotatedCommandHandler` logs a failed command at DEBUG, not ERROR | override `getExceptionLogLevel` | [§ 1.1.4](./RELEASE-NOTES-0.61.0.md#114-a-failed-command-is-no-longer-logged-at-error-by-annotatedcommandhandler-reactive) |
| The CDC inbox deletes a row once it is dispatched | `essentials.eventstore.cdc.cdc-dispatcher.dispatched-row-policy=MARK_DISPATCHED` | [§ 1.1.5](./RELEASE-NOTES-0.61.0.md#115-the-cdc-inbox-deletes-a-row-once-it-is-dispatched-postgresql-event-store) |
| Tracing and queue metrics interceptors follow the Micrometer beans, not `management.tracing.enabled` | the new `…tracing-enabled` / `…queue-metrics-enabled` switches | [§ 1.1.6](./RELEASE-NOTES-0.61.0.md#116-tracing-and-queue-metrics-interceptors-follow-the-micrometer-beans-not-managementtracingenabled-spring-boot-starters) |
| An `EventProcessor` overridden to `GlobalCompetingConsumers` on `PostgresqlDurableQueues`/MongoDB logs a WARN at start | - (remove the override, or move to the shard-owned engine and `PartitionedEventProcessor`, to keep order on several instances) | [§ 2.8](./RELEASE-NOTES-0.61.0.md#28-a-handler-that-outlasts-message-handling-timeout-is-logged-foundation-postgresql-queue) |
| Handlers and inner interceptors receive what an interceptor replaced; `PatternMatchingMessageHandler` honours `@InterceptorOrder` | explicit `@InterceptorOrder` | [§ 1.1.7](./RELEASE-NOTES-0.61.0.md#117-handlers-and-inner-interceptors-now-receive-what-an-interceptor-replaced-foundation-reactive) |
