# Essentials 0.50.1 — Release Notes

A patch release with five bug fixes. There are no API removals and no persisted-format changes. Upgrading from 0.50.0
needs no code changes. One thing is worth removing afterwards: see [§3](#3-jackson-3-only-applications-can-use-the-starters).

| # | Fix | Who is affected |
|---|---|---|
| 1 | [Polling event streams no longer leak their transaction](#1-polling-event-streams-no-longer-leak-their-transaction) | Everyone with asynchronous event-store subscriptions |
| 2 | [Jackson 3 `MismatchedInputException` is dead-lettered at once](#2-jackson-3-mismatchedinputexception-is-dead-lettered-at-once) | Durable-queue users on the Jackson 3 flavor |
| 3 | [Jackson 3-only applications can use the starters](#3-jackson-3-only-applications-can-use-the-starters) | Jackson 3 applications without Jackson 2 on the classpath |
| 4 | [`Optional` fields serialize under the Jackson 2 flavor](#4-optional-fields-serialize-under-the-jackson-2-flavor) | Jackson 2 flavor users with `Optional` in payloads |
| 5 | [The DevTools restart listener updates the Jackson 3 serializer](#5-the-devtools-restart-listener-updates-the-jackson-3-serializer) | Jackson 3 flavor users running Spring Boot DevTools |

---

## 1. Polling event streams no longer leak their transaction

**Symptom.** A PostgreSQL connection stays `idle in transaction` indefinitely after an event-store subscription is
unsubscribed, stopped, or loses its fenced lock. The connection holds a lock on the aggregate type's event table, so a
later `DROP`, `TRUNCATE`, `ALTER TABLE`, `VACUUM FULL` or `REINDEX` on that table waits forever. There is no error; a
migration simply hangs. The connection is also missing from the pool until the application restarts.

**Cause.** Each poll of `pollEvents` (used by all asynchronous subscriptions) and `unboundedPollForEvents` runs in a
`UnitOfWork`. An idle, caught-up subscriber checks on every 100th empty poll whether anything was persisted since the
last one (`SELECT MAX(global_order)`) and skips the poll when nothing was. That skip returned without committing or
rolling back the unit of work. The next poll on the same thread reused the unit of work and committed it, so in steady
state it went unnoticed. A subscription disposed before that next poll left it open for good. The defect dates back to
the code's import into this repository in 2022.

**Fix.** Every exit of a poll now ends its unit of work, and a `finally` block rolls back anything still open, so an
`Error` or a future early return cannot leak one again.

**Related fix.** The first poll of `unboundedPollForEvents` runs on the subscribing thread. If that thread was inside a
`UnitOfWork`, the poll joined it and then **committed it**, ending the caller's transaction early. A poll now only ends
a unit of work it started itself. On a polling error it marks a joined one rollback-only and leaves it to its owner, the
same rule `UnitOfWorkFactory.usingUnitOfWork` follows.

**Checking for it before upgrading.** Look for event-store connections that stay idle in a transaction:

```sql
SELECT pid, now() - xact_start AS open_for, left(query, 80) AS last_query
FROM pg_stat_activity
WHERE state = 'idle in transaction' AND query ILIKE 'SELECT MAX(%';
```

Terminating such a session (`SELECT pg_terminate_backend(pid)`) releases the lock, and the pool replaces the connection.

## 2. Jackson 3 `MismatchedInputException` is dead-lettered at once

The durable-queue consumers treat JSON that cannot be bound to the message type as a permanent error and send the
message to the dead-letter queue on its first failure. The check only recognised Jackson 2's
`com.fasterxml.jackson.databind.exc.MismatchedInputException`. Under the default Jackson 3 flavor, such messages were
therefore redelivered until the `RedeliveryPolicy` gave up. Both majors are now recognised, including subclasses such as
`InvalidFormatException`. On a runtime without Jackson 2, the old check also threw `NoClassDefFoundError` while handling
the original failure; that is fixed too.

## 3. Jackson 3-only applications can use the starters

Up to 0.50.0, the PostgreSQL, MongoDB and event-store starters failed at startup with
`NoClassDefFoundError: com/fasterxml/jackson/databind/Module` unless Jackson 2 was on the classpath, even for an
application on the Jackson 3 flavor. Jackson 3 applications had to add `com.fasterxml.jackson.core:jackson-databind` as a
workaround. **After upgrading you can remove that dependency**, unless something else in your application needs it.

One feature still needs Jackson 2 in 0.50.x: duplicate-notification filtering in `MultiTableChangeListener`. Its
`NotificationDuplicationFilter` SPI is typed on Jackson 2's `JsonNode`. Without Jackson 2, the listener logs a warning at
startup and delivers notifications unfiltered. That is correct, but it may trigger redundant polls. 0.60 moves the SPI to
Jackson 3.

## 4. `Optional` fields serialize under the Jackson 2 flavor

`JacksonJSONSerializer` (and therefore `JacksonJSONEventSerializer`) replaced its mapper's `TypeFactory` when setting
the class loader. That discarded the type modifier `Jdk8Module` installs, so any payload with an `Optional` field failed
with "Java 8 optional type … not supported by default", although `Jdk8Module` was registered. Such payloads now
serialize. No data was affected, because they never serialized before.

## 5. The DevTools restart listener updates the Jackson 3 serializer

After a Spring Boot DevTools restart, the starters point the `JSONSerializer` at the restarted context's class loader,
so payloads deserialize into the new generation of application classes. This only worked for the Jackson 2 serializer.
With the default Jackson 3 serializer it did nothing, which surfaced as `ClassCastException: X cannot be cast to X`
after a restart. It now works for any `JSONSerializer`.
