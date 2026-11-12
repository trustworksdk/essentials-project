# Draining LISTEN notifications under sustained traffic: options

Status: **investigation, nothing decided.** Measured 2026-11-12 on the branch `feature/event-store-redesign-phase0`. No
production code has changed; every candidate below is a test-only stand-in inside `ShardWakeupListenerDrainIT`.

## 1. The problem

pgjdbc 42.7.14's `PGConnection.getNotifications(timeout)` runs `QueryExecutorImpl.processNotifies(int)`. The timeout
applies only to the first message. After that the method loops for as long as `PGStream.hasMessagePending()` returns
true, holding the connection lock, and returns nothing until the loop ends. `hasMessagePending()` returns true when bytes
are already buffered. Otherwise it peeks the socket with a 1 ms `SO_TIMEOUT`, unless it is inside a one-second
cool-down that starts after a peek that found nothing. So a call returns only after a gap of more than about 1 ms in
the notification stream, and a stream that stays denser than that is held inside the driver for as long as it stays
dense.

The shard-owned queue's `ShardWakeupListener` (`getNotifications(200)`) therefore wakes cross-process owners late. At
1 000 notifications/s, p50 was 5-13 ms and p99 76-204 ms. At 3 000/s Tier 1 did no better than the 500 ms backstop. See
[`../design/durable-queue-measurements.md` §3.17](../design/durable-queue-measurements.md). The event-store
redesign's wake-up tier 1 ([`event-store-subscription-redesign.md`](event-store-subscription-redesign.md) §11, with
P0.10's revision) blocks on the same call, so whatever fixes the queue's listener should serve both.

pgjdbc master was checked on 2026-11-12 and has not changed: `processNotifies` still loops on
`timeoutMillis >= 0 || pgStream.hasMessagePending()`, and `PGStream` still has `minStreamAvailableCheckDelay = 1000`
and `nextStreamAvailableCheckTime`. The nearest upstream history is
[pgjdbc#1547](https://github.com/pgjdbc/pgjdbc/issues/1547) (fixed by PR #1548 in 2019). Before that fix, the
cool-down made `getNotifications()` return one notification per second. The fix keeps peeking for as long as a peek
finds bytes, which is the behaviour that now holds a dense stream back. No open issue about this was found.

## 2. How the candidates were measured

`ShardWakeupListenerDrainIT` (benchmark-gated) has the same shape as in §3.17: eight producer threads, one message per
transaction, 8 shards, a producing runtime whose wake-ups all go through `ShardWakeupNotifier`, and a consuming runtime
in the same JVM that stands in for another process. Each arm runs a 2 s warm-up and then 10 s measured. Latency is
from `enqueue` returning to the handler running.

Three arms were added (select arms with `-Ddrain.arms=`). Each runs with the backstop and both sweep intervals at 10 s, so
a late wake-up shows at full size; the baseline to compare with is `TIER1_LONG_BACKSTOP`.

- **`PINNED_PEEK`** (option 2, reflection). When the listener's connection is opened, one reflective write sets
  `PGStream.nextStreamAvailableCheckTime` to `Long.MAX_VALUE`. `hasMessagePending()` then never peeks the socket again
  and answers from `available()` alone (pgjdbc's buffer, then the socket's). `getNotifications(200)` is called exactly
  as today.
- **`QUERY_POLL`** (options 1 and 7, no reflection). The listener never enters `processNotifies`. Every millisecond it
  executes a prepared `SELECT 1`. The response is read up to ReadyForQuery, and every NotificationResponse that
  arrives before it lands in the executor's notification list. The listener then takes the list with
  `BaseConnection.getQueryExecutor().getNotifications()`, which does not touch the socket.
- **`PACED_NOTIFIER`** (option 5). The listener is unchanged. The producing runtime's notifier is held to one batch per
  2 ms, so the keys queued during the wait coalesce into the next batch, and each process's notifications arrive in
  bursts with a gap the 1 ms peek can see.

The tap that times every `getNotifications` call wraps the variants too, so the returns columns compare like with like.
One run per arm, in one invocation, on the devcontainer described in the measurements document's §4. The original
three arms were rerun in the same invocation as the baseline.

## 3. Results

| Offered | Arm | p50 (ms) | p90 (ms) | p99 (ms) | max (ms) | notifications | returns | largest | notifier batches |
|---|---|---|---|---|---|---|---|---|---|
| 200/s | stock, 10 s backstop | 1.76 | 2.31 | 2.91 | 6.44 | 2 400 | 2 369 | 5 | 2 395 |
| 200/s | stock, defaults | 1.91 | 2.40 | 2.98 | 5.89 | 2 400 | 2 388 | 3 | 2 400 |
| 200/s | backstop only | 249.0 | 450.7 | 495.0 | 503.2 | 2 400 | 2 352 | 3 | 2 399 |
| 200/s | **`PINNED_PEEK`** | **0.44** | 0.80 | **1.47** | 2.12 | 2 400 | 2 400 | 1 | 2 400 |
| 200/s | `QUERY_POLL` | 1.07 | 1.67 | 2.38 | 2.88 | 2 400 | 2 399 | 2 | 2 400 |
| 200/s | `PACED_NOTIFIER` | 1.89 | 2.41 | 2.81 | 3.52 | 2 400 | 2 400 | 1 | 2 400 |
| 1 000/s | stock, 10 s backstop | 98.3 | 337.2 | 7 035 | 17 012 | 12 000 | 352 | 403 | 11 990 |
| 1 000/s | stock, defaults | 13.8 | 68.8 | 164.0 | 273.1 | 12 000 | 786 | 220 | 11 979 |
| 1 000/s | backstop only | 254.5 | 455.0 | 630.5 | 966.1 | 12 000 | 835 | 121 | 11 992 |
| 1 000/s | **`PINNED_PEEK`** | **0.23** | 0.26 | **0.32** | 1.46 | 12 000 | 11 972 | 6 | 11 974 |
| 1 000/s | `QUERY_POLL` | 0.80 | 1.23 | 1.40 | 2.80 | 12 000 | 10 185 | 6 | 11 993 |
| 1 000/s | `PACED_NOTIFIER` | 3.78 | 4.71 | 5.33 | 16.14 | 12 000 | 5 221 | 8 | 5 221 |
| 3 000/s | stock, 10 s backstop | 3 865 | 37 849 | 52 136 | 52 925 | 35 998 | **15** | **10 329** | 35 892 |
| 3 000/s | stock, defaults | 0.19 | 0.26 | 0.43 | 3.59 | 36 000 | 35 847 | 8 | 35 868 |
| 3 000/s | backstop only | 447.5 | 1 436 | 2 217 | 2 577 | 35 997 | 21 | 9 724 | 35 838 |
| 3 000/s | **`PINNED_PEEK`** | **0.20** | 0.23 | **0.34** | 2.45 | 36 000 | 35 881 | 8 | 35 888 |
| 3 000/s | `QUERY_POLL` | 0.72 | 1.22 | 1.45 | 15.89 | 35 999 | 11 193 | 20 | 35 858 |
| 3 000/s | `PACED_NOTIFIER` | 1.10 | 3.97 | 4.61 | 7.20 | 35 901 | 5 504 | 8 | 5 504 |

"Returns" counts the `getNotifications` calls that handed over at least one notification. "Largest" is the most
notifications any one call returned. Notifications equal keys sent, because the notifier sends one key per shard per
batch.

- **`PINNED_PEEK` removes the lag at every rate.** One return per notification (at most 8 per call, that is, one
  notifier batch), p99 at or under 1.5 ms, and 0.3 ms at 1 000/s and 3 000/s. It is also faster than the stock
  listener at 200/s (p50 0.44 against 1.76 ms), because the stock call waits for its 1 ms peek to time out before it
  returns even a single notification.
- **`QUERY_POLL` removes the lag** at a cost of about one poll interval: p50 0.7-1.1 ms, p99 1.4-2.4 ms. The one
  15.9 ms maximum at 3 000/s is a single outlier in a single run. Its returns are fewer than its notifications because
  a 1 ms poll collects about three notifications at 3 000/s, which is expected and harmless.
- **`PACED_NOTIFIER` removes the lag for one producing process** and adds its own interval. The notifier sent about
  500 batches/s, as designed. p50 1.1-3.8 ms, p99 under 5.4 ms. The density argument in §4.5 is why this is not a fix.
- **The stock listener is bistable, not merely slow.** In this run the stock arm with the 10 s backstop was trapped at
  1 000/s and at 3 000/s (15 returns in 12 s at 3 000/s, one of them with 10 329 notifications, and the queue fell
  52 s behind). The stock arm at the defaults was not trapped at 3 000/s (35 847 returns, p50 0.19 ms), although in
  §3.17 the same arm was (16 returns). The backstop setting does not touch the listener's socket, so the difference is
  the driver's state, not the arm. Once a peek misses, the one-second cool-down puts `hasMessagePending()` into
  `available()`-only mode. In that mode calls return promptly, which is exactly what `PINNED_PEEK` makes permanent.
  When the cool-down expires, the next peek either misses (the cool-down is re-armed and the listener stays fast) or
  hits (the loop holds on for as long as the peeks keep hitting). Which one happens is timing. So the stock listener
  can look healthy in one run and fall back to the backstop in the next at the same rate. That makes "it was fine in
  our test" no evidence.

## 4. Options

| # | Option | Removes the lag? | Numbers | Dependencies | Risks | Effort |
|---|---|---|---|---|---|---|
| 1 | pgjdbc public API: `getNotifications()` / `(0)` / `(1)`, `socketTimeout`, properties, newer pgjdbc | No | Same loop (§4.1); master unchanged | None | Bistable as today | None |
| 1+7 | `QUERY_POLL`: trivial statement each 1 ms, take notifications from `BaseConnection.getQueryExecutor()` | Yes | p50 0.7-1.1 ms, p99 1.4-2.4 ms, all rates | None (pgjdbc internal-but-public interfaces) | One statement per ms per JVM while idle; latency floor = poll interval; `org.postgresql.core` has no compatibility promise | Small (about 30 lines) |
| 2 | `PINNED_PEEK`: one reflective field write per listener connection | Yes | p50 0.20-0.44 ms, p99 0.32-1.47 ms, about 1 notification per return | None | Two private field names (unchanged since 2019); must restore or evict before the connection goes back to a pool; falls back to stock behaviour if a field disappears | Small (about 40 lines plus an upgrade-guard test) |
| 3 | Minimal LISTEN-only protocol client | Yes (by construction) | Not built | None, or `scram-client` | Needs its own host/credentials/TLS configuration (a `DataSource` cannot supply them); missing auth modes (SCRAM-PLUS, GSS, IAM plugins); our own wire-protocol code to maintain | Large (600-900 lines plus tests) |
| 4 | vertx-pg-client or r2dbc-postgresql for the listener | Yes (per-message push) | Not built | Heavy: Netty plus Vert.x or Reactor, `provided` for every user | As option 3 for configuration, plus Netty version conflicts | Medium |
| 5 | Pace the notifier (batch at least 2 ms apart) | Only for one producing process | p50 1.1-3.8 ms, p99 4.6-5.3 ms | None | Several processes, or the event store's in-transaction notify, bring the density back; adds up to the interval to every wake-up | Small |
| 6 | Upstream pgjdbc fix | Yes, eventually | `PINNED_PEEK` shows what it would deliver | None | Release timing outside our control; users run older drivers | Small patch; issue draft in §6 |

### 4.1 pgjdbc API without reflection

Every public way into `processNotifies` ends in the same loop.

- **`getNotifications()` with no argument** is `processNotifies(-1)`, a bare `while (hasMessagePending())`. Inside the
  one-second cool-down it reads only what `available()` reports. On a plain TCP socket that is exact and does not
  block. On an SSL socket the JDK reports only bytes that are already decrypted, so notifications can sit unseen for up
  to a second; the pgsql-jdbc archive has a 2009 thread that recommends dummy statements on SSL for this reason. Outside
  the cool-down it peeks, and a peek that hits leads straight into the dense-stream loop. A polling loop over the
  no-argument call is therefore trapped again once a second under sustained traffic. `MultiTableChangeListener` polls
  this way today. This option was not prototyped, because the trap follows from the code above.
- **`getNotifications(0)` and `getNotifications(1)`.** The argument bounds only the wait for the first message. A
  dedicated thread that calls `getNotifications(1)` in a loop (option 7) hits the same loop as `(200)`.
- **`socketTimeout` and other connection properties** cannot help. `hasMessagePending()` sets `SO_TIMEOUT` to 1 ms for
  its peek whatever the connection's setting is. No connection property sets `minStreamAvailableCheckDelay`; only
  the replication protocol calls the setter, and it sets 0, which means peek every time.
- **A query on the listener connection** is the one route that does not enter `processNotifies`. A statement's
  response ends at ReadyForQuery, so draining it is bounded, and notifications that arrive while it runs are queued in
  the executor. `QUERY_POLL` is this route. Taking the queued notifications without going back into `processNotifies`
  needs `BaseConnection.getQueryExecutor().getNotifications()`: public interfaces in `org.postgresql.core`, but
  internal ones, with no compatibility promise. `PGConnection.getNotifications()` would take them too, but it calls
  `processNotifies` first and can be trapped there.

### 4.2 Reflection into pgjdbc internals

`PINNED_PEEK` needs two private fields: `QueryExecutorBase.pgStream` and `PGStream.nextStreamAvailableCheckTime`. Both
have kept their names since #1548 (2019). The alternative, owning the socket wait and parsing frames through
`PGStream`, would touch several more private methods and was not pursued.

- **JPMS and `--release 25`.** pgjdbc has no `module-info`; its manifest declares `Automatic-Module-Name:
  org.postgresql.jdbc`. On the class path it is in the unnamed module, and on the module path it is an automatic module.
  Both open every package, so `setAccessible(true)` works without `--add-opens`. `--release 25` restricts only JDK
  APIs, and deep reflection into the JDK is not needed.
- **Thread safety and the connection lock.** The write happens on the listener thread, before that thread first calls
  `getNotifications`, and nothing else uses the connection. The lock is not involved.
- **SSL.** It is safe. The first message is still a blocking read with the caller's timeout. After it, the loop
  drains only what pgjdbc has already buffered (each fill reads up to 8 KiB, so it holds several frames) and what the
  socket reports as available. Anything after that waits for the next call's blocking read, not for a peek.
- **Reconnect and re-LISTEN.** The write is per physical connection, so it goes after each `getConnection()` in the
  existing reconnect loop, next to `LISTEN`.
- **Hikari and raw connections.** `unwrap(PGConnection.class)` returns the `PgConnection` in both cases. Hikari does not
  validate a connection while it is checked out. One side effect needs care: the pinned field outlives the checkout.
  A later borrower that calls `getNotifications()` or uses `COPY` on that physical connection would never peek. The
  listener should restore the field (write 0) before it returns the connection, or evict it with
  `HikariPoolMXBean.softEvictConnections` / `Connection.abort`.
- **pgjdbc upgrades.** If a field is renamed, the lookup fails. The listener would log once and fall back to the stock
  call, which is today's behaviour. Correctness never depends on Tier 1, so this is the right failure mode. A unit
  test that looks up both fields against the resolved driver turns a rename into a build failure at upgrade time
  rather than a silent latency regression.

### 4.3 A minimal LISTEN-only protocol client

Estimated size, reasoned and not prototyped: 600 to 900 lines plus tests. That covers the startup message,
cleartext, MD5 and SCRAM-SHA-256 authentication, `SSLRequest` with pgjdbc's `sslmode` semantics (disable, allow,
prefer, require, verify-ca, verify-full, plus root certificate and key handling), the `LISTEN` round trip, a frame
reader for `NotificationResponse`, `ErrorResponse`, `NoticeResponse` and `ParameterStatus`, keepalive, and
reconnect. SCRAM is available inside pgjdbc only as `org.postgresql.shaded.com.ongres.scram`, which is relocated and
internal. Depending on it is as fragile as the reflection; the alternative is a new `com.ongres.scram:scram-client`
dependency. SCRAM-SHA-256-PLUS, GSSAPI/Kerberos, SSPI and pgjdbc's `AuthenticationPlugin` (cloud IAM tokens) would be
missing.

The decisive problem is not size. The listener today takes a `DataSource`, and a `DataSource` hands out connections,
not credentials. A separate client needs host, port, user, password and TLS settings configured separately: a new
configuration surface for every user, a second place for secrets, and authentication modes that pgjdbc supports and the
client does not. Not prototyped.

### 4.4 Another driver for the listener only

vertx-pg-client and r2dbc-postgresql both deliver notifications per message, so either would remove the lag. Both have
the same `DataSource` problem as §4.3. Both also bring an event-loop stack: vertx-pg-client needs `vertx-core` and
Netty, and r2dbc-postgresql needs Reactor, reactor-netty and Netty. That is several megabytes of jars, and with Netty's
version alignment it is a frequent conflict point in Spring Boot applications. Under this project's rule that
third-party integrations are `provided` scope, every user of the queue or of the event store's tier 1 would have to
add that stack to get low-latency wake-ups. A missing jar would show up as a silent fallback to the backstop or as a
startup failure. Not prototyped.

### 4.5 Pacing the notifier

See `PACED_NOTIFIER` in §3. Pacing changes only how one process sends. The driver's loop sees the sum over every
producing process and every queue on the database, because the channel is global. N processes that each pace at 2 ms
still put a notification on the listener's socket every 2/N ms, and from about N = 2 the gaps fall under the driver's
1 ms again. The event store's per-commit `pg_notify` inside the appending transaction is not batched by any notifier at
all. Pacing also adds up to its interval to every wake-up. At best it is a mitigation for single-producer
deployments.

### 4.6 Upstream

A fix belongs in pgjdbc. The smallest change that keeps today's contract (everything that has already arrived comes
back in one call) is to stop peeking once the call has read a notification. After the first message, the loop
continues only while bytes are already buffered or reported by `available()`. That is the behaviour `PINNED_PEEK`
produces from outside. The draft issue is in §6. Even if it is accepted, the fix reaches users only through a pgjdbc
release, and Essentials cannot require a driver version newer than its users run, so a local fix is needed either way.

## 5. Recommendation

**This is a recommendation, not a decision.**

Adopt `PINNED_PEEK` for `ShardWakeupListener` and for the event store's tier-1 listener, with `QUERY_POLL` as the
fallback when the reflective lookup fails, and file the upstream issue in §6.

- `PINNED_PEEK` is the only candidate that brings Tier 1 to the latency it was designed for at every rate measured
  (p99 0.3 ms at 3 000/s, against a stock listener that randomly falls to the backstop). It needs no dependency, no
  extra statements and no new configuration, and it works with Hikari and raw connections alike.
- Its risk is a pgjdbc rename. Contain it three ways: a unit test that looks up both fields against the resolved
  driver, so an upgrade that breaks it fails the build; a runtime fallback, so a user on some other driver build still
  gets a working listener; and restoring the field (or evicting the connection) before the listener releases it.
- The fallback should be `QUERY_POLL` rather than the stock call, because the stock call is bistable. Its 1 ms poll can
  back off while idle (for example, doubling up to 50 ms after consecutive empty polls). That trades the idle cost
  against first-message latency, and the trade-off needs its own measurement before anyone picks numbers.
- Once a pgjdbc release carries the upstream change, the reflection can be gated on the driver version and eventually
  dropped.
- Not recommended: options 3 and 4, whose configuration cost (a second set of credentials beside the `DataSource`)
  outweighs a problem that a 40-line change removes; and pacing as the fix, though it stays harmless to keep.

Open before deciding: an SSL run of `PINNED_PEEK`, a multi-process producer run, and `QUERY_POLL`'s idle cost.

## 6. Upstream issue draft (not posted)

> **Title:** `getNotifications(timeout)` does not return while notifications keep arriving less than ~1 ms apart
>
> **Driver version:** 42.7.14 (checked against master on 2026-11-12). PostgreSQL 17.
>
> **What happens.** `PgConnection.getNotifications(int)` calls `QueryExecutorImpl.processNotifies(int)`. The timeout
> applies to the first message only. After each `'A'` message, `timeoutMillis` is set to -1 and the loop
> `while (timeoutMillis >= 0 || pgStream.hasMessagePending())` continues. `PGStream.hasMessagePending()` returns true
> when bytes are buffered. Otherwise, outside the one-second cool-down introduced by #1548, it peeks the socket with
> `SO_TIMEOUT = 1`. Each successful peek keeps the loop going, and the notifications it has read are not returned until
> a peek times out. On a connection that receives notifications less than about 1 ms apart, a single
> `getNotifications(200)` call therefore runs for as long as the traffic stays that dense, while holding the connection
> lock. Nothing bounds it by count or by time.
>
> **Measured impact.** One `LISTEN` connection receives wake-up hints from eight producers at a steady rate. At
> 1 000 notifications/s, wake-up latency was p50 5-13 ms and p99 76-204 ms, against about 2 ms at 200/s. At
> 3 000/s the listener returned 15-25 times in 12 s, once with 10 329 notifications, and single calls took several
> seconds. Whether a run gets trapped is timing-dependent: the same configuration has also run untrapped. With the
> peek disabled after the first message (see below), the same load gave p50 0.23 ms and p99 0.32 ms at 1 000/s, and
> p99 0.34 ms at 3 000/s, with about one notification per call.
>
> **Why `getNotifications()` / `getNotifications(0)` / `socketTimeout` do not help.** All of them enter the same loop.
> `hasMessagePending()` overrides `SO_TIMEOUT` for its peek, and `minStreamAvailableCheckDelay` cannot be set through
> a connection property.
>
> **Proposed change.** Once `processNotifies` has read at least one notification, continue only while data is already
> available (`pgInput.available() > 0`) and do not peek the socket. Notifications that have already reached the client
> are still returned together, which is what #1547 asked for. The next call's blocking read picks up anything that
> arrives later, so nothing waits on a 1 ms peek. As a sketch, split `hasMessagePending()` into a buffered-only check
> and the existing peeking one, and use the buffered-only check in the `'A'` branch of the loop. `processNotifies(-1)`
> (non-blocking) keeps one peek before its first message, so a poller still sees notifications on SSL sockets, where
> `available()` can return 0.
>
> An alternative that changes less is a bound on the drain (for example, return after the caller's timeout has elapsed
> or after N notifications). That would limit the damage but still delay every notification in a dense stream by up to
> the bound.
>
> **Reproducer.** One connection runs `LISTEN c` and loops on `getNotifications(200)`, recording how many notifications
> each call returns and how long the call took. A second connection runs
> `SELECT pg_notify('c', g::text) FROM generate_series(1, 10)` every 2-3 ms (or several threads at 1 000+/s in total).
> Calls return hundreds or thousands of notifications at once, after seconds, instead of a few after milliseconds.
>
> I am happy to open a PR with the change and a test.

## 7. Not measured

- SSL connections (all arms ran over plain TCP inside Docker). §4.2's SSL reasoning comes from reading the code, not
  from a run.
- Several producing processes. The density argument in §4.5 is arithmetic.
- `QUERY_POLL`'s idle cost on the server (one statement per millisecond per listening JVM) and any back-off schedule.
- One run per arm on a noisy host (§4 of the measurements document). Treat differences under about 2x in the tail as
  noise.
- Options 3 and 4 were reasoned about and not built.
