/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.Pg;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.WakeupNotifyCostSpikeIT.Lat;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.WakeupNotifyCostSpikeIT.Notifier;
import io.vertx.core.*;
import io.vertx.pgclient.*;
import io.vertx.pgclient.pubsub.PgSubscriber;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.*;
import org.postgresql.core.*;

import java.lang.management.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Spike P0.17 part (a) of {@code docs/archive/event-store-subscription-redesign.md} §18: does a client that delivers
 * LISTEN notifications as they arrive (the Vert.x PG client) beat pgjdbc's notification drain on wake latency under
 * sustained traffic? Part (b), request-path pipelining, is parked and not built here.
 * <p>
 * Background: {@code docs/plans/pgjdbc-notification-drain-options.md}. Under continuous notification traffic pgjdbc's
 * {@code getNotifications(timeout)} keeps reading while bytes are pending and hands nothing out until the stream
 * pauses; P0.10 measured p50 3.4-15.9 ms after commit on a real server against 0.02-1.06 ms on the wire. Listener arms,
 * each on its own LISTEN connection (role {@code listener}):
 * <ul>
 *     <li>{@code STOCK}: pgjdbc {@code getNotifications(200)}, unchanged (the queue's {@code ShardWakeupListener});</li>
 *     <li>{@code PINNED_PEEK}: as STOCK after one reflective write pinning {@code PGStream.nextStreamAvailableCheckTime}
 *     at {@code Long.MAX_VALUE}, so {@code hasMessagePending()} never does its 1 ms socket peek (ported from
 *     {@code ShardWakeupListenerDrainIT} in postgresql-queue-shard-owned, not depended on);</li>
 *     <li>{@code QUERY_POLL}: no reflection; every millisecond {@code SELECT 1}, then the notifications the response
 *     carried, taken from {@code QueryExecutor.getNotifications()} (ported likewise);</li>
 *     <li>{@code VERTX}: Vert.x {@code PgSubscriber} with a channel handler, which Netty calls as each
 *     NotificationResponse is decoded.</li>
 * </ul>
 * The pgjdbc arms' sockets are tapped ({@link NotifyTapSocketFactory}), so their wire arrival is reported too: the
 * floor any listener could reach on that connection. Vert.x's handler runs on the Netty event loop right after the
 * read, so its callback is its wire time plus decoding.
 * <p>
 * Load: {@code W} producer threads ({@code -Dspike.listen.writers}, default {@code 8}; a list runs each), each on its
 * own autocommit connection, commit one {@code INSERT} per notification on a schedule that holds the rate; after the
 * commit returns, the payload goes to the coalesced after-commit notifier (the engine's chosen delivery,
 * {@link Notifier}, copied from the queue's {@code ShardWakeupNotifier} for P0.10). Rates
 * ({@code -Dspike.listen.rates}, default {@code 100,1000,3000,burst2000}): {@code N} notifications per second, evenly
 * spaced; {@code burstN}: every second {@code N} commits released at once. Every notification carries a unique id, so
 * every one is timed (commit returned in the producer → handed to the listener code) and counted (received = sent).
 * <p>
 * Per writer count and rate, the arms run in a palindrome ({@code -Dspike.listen.arms}, default all four, and back),
 * each window with its own listener connection, a {@code -Dspike.listen.warmupMillis} (default 2000) warm-up at the
 * same rate and {@code -Dspike.listen.seconds} (default 10) measured. Measured per window: latency p50/p99/max;
 * notifications sent/received/duplicates; listener-side client CPU (the arm's listener threads, from
 * {@link ThreadMXBean}); server CPU per notification (the container's cgroup, or {@code -Dspike.serverCpuCommand};
 * it includes the producers' commits, the same in every arm). Passes: a container runs {@code fsync=on}
 * ({@code -Dspike.listen.fsync}, a list); an external server one pass with its own settings. Results:
 * {@code target/spike-results/p0-17-listen.txt}, with a GATE line per writer count: an alternative listener is worth
 * it only if pgjdbc's best arm misses p99 ≤ 2 ms at 3 000 notifications/s and the alternative meets it.
 * <p>
 * Not built: the row's minimal LISTEN-only wire reader, and producers through the engine's append (P0.10 measured
 * that; here a plain INSERT commit, so the producers' cost does not vary by arm).
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class AlternativeClientSpikeIT {
    private static final String RESULTS          = "p0-17-listen.txt";
    private static final String CHANNEL          = "essentials_eventstore";
    private static final String TABLE            = "p017_commits";
    private static final int    SECONDS          = Integer.getInteger("spike.listen.seconds", 10);
    private static final int    WARMUP_MILLIS    = Integer.getInteger("spike.listen.warmupMillis", 2000);
    private static final int    PGJDBC_TIMEOUT   = 200;
    private static final long   QUERY_POLL_NANOS = 1_000_000;
    private static final double GATE_P99_MS      = 2.0;
    private static final int    GATE_RATE        = 3000;

    enum Arm {
        STOCK, PINNED_PEEK, QUERY_POLL, VERTX;

        boolean pgjdbc() {
            return this != VERTX;
        }
    }

    /** {@code perSecond} notifications per second, evenly spaced, or in one burst at the start of every second. */
    record Rate(int perSecond, boolean burst) {
        static Rate parse(String s) {
            s = s.trim();
            return s.startsWith("burst") ? new Rate(Integer.parseInt(s.substring(5)), true) : new Rate(Integer.parseInt(s), false);
        }

        /** When ticket {@code i} may commit, nanoseconds after the window's start. */
        long scheduledAt(long i) {
            return burst ? (i / perSecond) * 1_000_000_000L : i * 1_000_000_000L / perSecond;
        }

        @Override
        public String toString() {
            return burst ? "burst " + perSecond + "/1s" : perSecond + "/s";
        }
    }

    /** One window's p99 per arm and rate, for the gate. */
    private final Map<String, List<Double>> p99s = new LinkedHashMap<>();

    @Test
    void listener_wake_latency() throws Exception {
        var writerCounts = Arrays.stream(System.getProperty("spike.listen.writers", "8").split(",")).map(String::trim).map(Integer::parseInt).toList();
        var rates        = Arrays.stream(System.getProperty("spike.listen.rates", "100,1000,3000,burst2000").split(",")).map(Rate::parse).toList();
        var arms         = Arrays.stream(System.getProperty("spike.listen.arms", "STOCK,PINNED_PEEK,QUERY_POLL,VERTX").split(",")).map(String::trim).map(Arm::valueOf).toList();
        var passes       = SpikeServer.external() ? List.of("server") : Arrays.stream(System.getProperty("spike.listen.fsync", "on").split(",")).map(String::trim).toList();
        for (var pass : passes) {
            try (var pg = Pg.start("on".equals(pass))) {
                for (var role : List.of("notifier", "listener", "producer")) pg.server.createRole(pg.observer, role);
                try (var s = pg.observer.createStatement()) {
                    s.execute("CREATE TABLE " + TABLE + " (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, at bigint NOT NULL)");
                }
                record("");
                record("=== LISTEN | " + Instant.now() + " | " + SpikeServer.target("postgres:17.5 (P0.0 container) started with fsync=" + pass) + " | settings "
                               + settings(pg) + " | server CPU " + pg.server.cpuSource() + " | client CPUs " + Runtime.getRuntime().availableProcessors() + " | pgjdbc "
                               + org.postgresql.util.DriverInfo.DRIVER_VERSION + ", Vert.x " + VertxVersion.get() + " | writers " + writerCounts + " | rates " + rates
                               + " | arms " + arms + " palindrome per rate | " + SECONDS + " s windows after a " + WARMUP_MILLIS + " ms warm-up, fresh listener connection per window"
                               + " | producers: autocommit INSERT, then the coalesced after-commit notifier (pg_notify … FROM unnest) | pgjdbc getNotifications(" + PGJDBC_TIMEOUT
                               + "), QUERY_POLL every " + QUERY_POLL_NANOS / 1000 + " µs | latency: commit returned in the producer -> listener callback, every notification");
                try (var vertx = new VertxHolder()) {
                    for (int writers : writerCounts) {
                        p99s.clear();
                        for (var rate : rates) {
                            var order = new ArrayList<>(arms);
                            var back  = new ArrayList<>(arms);
                            Collections.reverse(back);
                            order.addAll(back);
                            for (var arm : order) window(pg, vertx.vertx, writers, rate, arm, pass);
                        }
                        gate(writers, arms, rates);
                    }
                }
            }
        }
    }

    private static String settings(Pg pg) throws SQLException {
        var sb = new StringJoiner(", ");
        for (var name : List.of("fsync", "synchronous_commit", "max_connections", "server_version")) {
            sb.add(name + "=" + SpikePostgres.queryString(pg.observer, "SELECT current_setting('" + name + "')"));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ one window

    private static final AtomicInteger WINDOWS = new AtomicInteger();

    private void window(Pg pg, Vertx vertx, int writers, Rate rate, Arm arm, String pass) throws Exception {
        var  windowId = "w" + WINDOWS.incrementAndGet();
        int  total    = rate.perSecond() * SECONDS;
        var  run      = new Run(windowId, total);
        try (var listener = arm.pgjdbc() ? new PgjdbcListener(pg, arm, run) : new VertxListener(pg, vertx, run);
             var notifier = new Notifier(pg);
             var producers = new Producers(pg, writers)) {
            // warm-up at the same rate under another id, then let it drain
            var warm = new Run(windowId + "warm", rate.perSecond() * Math.max(1, WARMUP_MILLIS / 1000));
            listener.run = warm;
            producers.run(rate, warm, notifier);
            notifier.awaitIdle();
            Thread.sleep(300);
            listener.run = run;
            notifier.resetCounters();

            var  threads = ManagementFactory.getThreadMXBean();
            long cpu0    = listener.cpuNanos(threads);
            long server0 = pg.server.cpuMicros();
            long wall0   = System.nanoTime();
            producers.run(rate, run, notifier);
            long produceNanos = System.nanoTime() - wall0;
            notifier.awaitIdle();
            // drain: until every notification arrived, or nothing arrived for 2 s
            long last = -1, quietSince = System.nanoTime();
            while (run.received.get() < total && System.nanoTime() - quietSince < 2_000_000_000L) {
                Thread.sleep(20);
                if (run.received.get() != last) {
                    last = run.received.get();
                    quietSince = System.nanoTime();
                }
            }
            long   cpu      = listener.cpuNanos(threads) - cpu0;
            double serverCpu = SpikeServer.cpuDelta(server0, pg.server.cpuMicros());
            double seconds  = produceNanos / 1e9;

            var cb   = new Lat();
            var wire = new Lat();
            long committed = 0;
            for (int i = 0; i < total; i++) {
                long c = run.committedAt.get(i);
                if (c == 0) continue;
                committed++;
                long r = run.receivedAt.get(i), w = run.wireAt.get(i);
                if (r != 0) cb.add(r - c);
                if (w != 0) wire.add(w - c);
            }
            long sent = notifier.keysSent.sum();
            p99s.computeIfAbsent(arm + "@" + rate, k -> new ArrayList<>()).add(cb.p(0.99));
            record(String.format("LISTEN %s | %2d writers | %-13s | %-11s | committed %6d (%6.0f/s, scheduled %d), commit failures %d | notifications sent %d in %d batches (%.1f per batch), "
                                         + "received %d%s, duplicates %d, in %d listener reads (at most %d per read)",
                                 pass, writers, rate, arm, committed, committed / seconds, total, run.failures.get(), sent, notifier.batchesSent.sum(),
                                 sent / (double) Math.max(1, notifier.batchesSent.sum()), run.received.get(),
                                 run.received.get() == sent ? " (= sent)" : " (MISSING " + (sent - run.received.get()) + ")",
                                 run.duplicates.get(), run.reads.get(), run.maxBatch.get()));
            record(String.format("  wake latency, commit returned -> listener callback: %s | -> on the listener's socket (wire): %s",
                                 cb.summary(), arm.pgjdbc() ? wire.summary() : "n/a (Vert.x: callback runs on the read)"));
            record(String.format("  listener client CPU %.1f µs per notification (%.3f cores, threads %s) | PostgreSQL CPU %.1f µs per notification incl. the producers' commits (%.2f cores)",
                                 cpu / 1000.0 / Math.max(1, run.received.get()), cpu / 1e9 / seconds, listener.threadNames(), serverCpu / Math.max(1, sent), serverCpu / 1e6 / seconds));
            record(String.format("CSV,%s,%d,%s,%s,%d,%.0f,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.1f,%.1f",
                                 pass, writers, rate.burst() ? "burst" : "rate", arm, rate.perSecond(), committed / seconds, sent, run.received.get(), run.duplicates.get(),
                                 cb.p(0.5), cb.p(0.99), cb.p(1.0), wire.p(0.5), wire.p(0.99), cpu / 1000.0 / Math.max(1, run.received.get()), serverCpu / Math.max(1, sent)));
            if (run.firstFailure != null) record("  first commit failure: " + run.firstFailure);
            if (listener.failure != null) record("  listener failure: " + listener.failure);
        } finally {
            try (var s = pg.observer.createStatement()) {
                s.execute("TRUNCATE " + TABLE);
            }
        }
    }

    /**
     * The row's gate: pgjdbc's best arm (lowest of the arms' worse window p99) at 3 000/s against 2 ms, and whether
     * Vert.x meets it.
     */
    private void gate(int writers, List<Arm> arms, List<Rate> rates) {
        var at = rates.stream().filter(r -> !r.burst() && r.perSecond() == GATE_RATE).findFirst();
        if (at.isEmpty()) {
            record("GATE " + writers + " writers: not evaluated (no " + GATE_RATE + "/s rate in this run)");
            return;
        }
        String bestArm = null;
        double best    = Double.POSITIVE_INFINITY;
        var    j       = new StringJoiner(", ");
        for (var arm : arms) {
            var w = p99s.getOrDefault(arm + "@" + at.get(), List.of()).stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            j.add(String.format("%s %.3f ms", arm, w));
            if (arm.pgjdbc() && w < best) {
                best = w;
                bestArm = arm.name();
            }
        }
        var vertx = p99s.getOrDefault(Arm.VERTX + "@" + at.get(), List.of()).stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        String verdict;
        if (bestArm == null) verdict = "no pgjdbc arm ran";
        else if (best <= GATE_P99_MS) verdict = "pgjdbc's best (" + bestArm + ") meets p99 <= " + GATE_P99_MS + " ms: pgjdbc only";
        else if (Double.isNaN(vertx)) verdict = "pgjdbc's best (" + bestArm + ") misses p99 <= " + GATE_P99_MS + " ms; VERTX did not run";
        else if (vertx <= GATE_P99_MS) verdict = "pgjdbc's best (" + bestArm + ") misses p99 <= " + GATE_P99_MS + " ms and VERTX meets it: an alternative listener qualifies";
        else verdict = "pgjdbc's best (" + bestArm + ") misses p99 <= " + GATE_P99_MS + " ms, but so does VERTX: no alternative qualifies";
        record(String.format("GATE %d writers at %s, worse window p99 per arm: %s | %s", writers, at.get(), j, verdict));
    }

    // ------------------------------------------------------------------ timing state

    /** One window's tickets, commit times and receive times, indexed by notification id. */
    static final class Run {
        final String           id;
        final int              total;
        final AtomicLongArray  committedAt;
        final AtomicLongArray  receivedAt;
        final AtomicLongArray  wireAt;
        final AtomicLong       received   = new AtomicLong();
        final AtomicLong       duplicates = new AtomicLong();
        final AtomicLong       reads      = new AtomicLong();
        final AtomicLong       maxBatch   = new AtomicLong();
        final AtomicLong       failures   = new AtomicLong();
        final AtomicLong       next       = new AtomicLong();
        volatile Throwable     firstFailure;

        Run(String id, int total) {
            this.id = id;
            this.total = total;
            committedAt = new AtomicLongArray(total);
            receivedAt = new AtomicLongArray(total);
            wireAt = new AtomicLongArray(total);
        }

        String payload(int i) {
            return id + "|" + i;
        }

        /** The notification id in {@code payload} if it belongs to this run, else -1. */
        int idOf(String payload) {
            int bar = payload.indexOf('|');
            if (bar != id.length() || !payload.startsWith(id)) return -1;
            int i = Integer.parseInt(payload, bar + 1, payload.length(), 10);
            return i < total ? i : -1;
        }

        void received(String payload, long now) {
            int i = idOf(payload);
            if (i < 0) return;
            if (receivedAt.compareAndSet(i, 0, now)) received.incrementAndGet();
            else duplicates.incrementAndGet();
        }

        void wire(String payload, long now) {
            int i = idOf(payload);
            if (i >= 0) wireAt.compareAndSet(i, 0, now);
        }

        void read(int n) {
            reads.incrementAndGet();
            maxBatch.accumulateAndGet(n, Math::max);
        }
    }

    // ------------------------------------------------------------------ producers

    /** {@code writers} autocommit connections committing one INSERT per ticket, on the rate's schedule. */
    static final class Producers implements AutoCloseable {
        private final List<Connection>      connections = new ArrayList<>();
        private final ExecutorService       pool;

        Producers(Pg pg, int writers) throws SQLException {
            for (int i = 0; i < writers; i++) connections.add(DriverManager.getConnection(pg.jdbcUrl(), pg.server.role("producer"), pg.server.password("producer")));
            pool = Executors.newFixedThreadPool(writers, Thread.ofPlatform().name("producer-", 0).factory());
        }

        void run(Rate rate, Run run, Notifier notifier) throws Exception {
            long t0 = System.nanoTime();
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var c : connections) {
                futures.add(pool.submit(() -> {
                    try (var ps = c.prepareStatement("INSERT INTO " + TABLE + " (at) VALUES (?)")) {
                        while (true) {
                            long i = run.next.getAndIncrement();
                            if (i >= run.total) return null;
                            long due = t0 + rate.scheduledAt(i);
                            for (long now; (now = System.nanoTime()) < due; ) LockSupport.parkNanos(due - now);
                            try {
                                ps.setLong(1, i);
                                ps.executeUpdate();
                            } catch (SQLException e) {
                                run.failures.incrementAndGet();
                                if (run.firstFailure == null) run.firstFailure = e;
                                continue;
                            }
                            run.committedAt.set((int) i, System.nanoTime());
                            notifier.submit(run.payload((int) i));
                        }
                    }
                }));
            }
            for (var f : futures) f.get(run.total / Math.max(1, rate.perSecond()) + 120L, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            pool.shutdownNow();
            for (var c : connections) c.close();
        }
    }

    // ------------------------------------------------------------------ listeners

    abstract static sealed class Listener implements AutoCloseable permits PgjdbcListener, VertxListener {
        /** Swapped between warm-up and measurement; notifications of another run are ignored. */
        volatile Run       run;
        volatile Throwable failure;

        Listener(Run run) {
            this.run = run;
        }

        /** The CPU time of the threads that do this arm's listening, ns. */
        abstract long cpuNanos(ThreadMXBean threads);

        abstract String threadNames();
    }

    /** A pgjdbc LISTEN connection drained by one thread, as STOCK, PINNED_PEEK or QUERY_POLL. */
    static final class PgjdbcListener extends Listener {
        private final Connection connection;
        private final Thread     thread;
        private volatile boolean stop;

        PgjdbcListener(Pg pg, Arm arm, Run run) throws Exception {
            super(run);
            var url = pg.jdbcUrl();
            url = url + (url.contains("?") ? "&" : "?") + "sslmode=disable&socketFactory=" + NotifyTapSocketFactory.class.getName();
            NotifyTapSocketFactory.sink = tap -> this.run.wire(tap.payload(), tap.nanos());
            connection = DriverManager.getConnection(url, pg.server.role("listener"), pg.server.password("listener"));
            try (var s = connection.createStatement()) {
                s.execute("LISTEN " + CHANNEL);
            }
            var pgc = connection.unwrap(PGConnection.class);
            if (arm == Arm.PINNED_PEEK) pinPeek(pgc);
            var poll = arm == Arm.QUERY_POLL ? connection.prepareStatement("SELECT 1") : null;
            var base = (BaseConnection) pgc;
            thread = Thread.ofPlatform().name("p017-listener").start(() -> {
                while (!stop) {
                    try {
                        var ns = poll != null ? queryPoll(poll, base) : pgc.getNotifications(PGJDBC_TIMEOUT);
                        if (ns == null || ns.length == 0) continue;
                        long now = System.nanoTime();
                        var  r   = this.run;
                        r.read(ns.length);
                        for (var n : ns) r.received(n.getParameter(), now);
                    } catch (SQLException e) {
                        if (!stop) failure = e;
                        return;
                    }
                }
            });
        }

        /**
         * The PINNED_PEEK candidate's one reflective write (ShardWakeupListenerDrainIT): {@code hasMessagePending()}
         * skips its 1 ms socket peek while {@code now < nextStreamAvailableCheckTime}; pinned at {@code Long.MAX_VALUE}
         * it answers from {@code available()} alone. The first message of a {@code getNotifications(timeout)} call is
         * still a blocking read with the timeout.
         */
        static void pinPeek(PGConnection pg) throws Exception {
            var executor    = ((BaseConnection) pg).getQueryExecutor();
            var streamField = QueryExecutorBase.class.getDeclaredField("pgStream");
            streamField.setAccessible(true);
            var stream = (PGStream) streamField.get(executor);
            var next   = PGStream.class.getDeclaredField("nextStreamAvailableCheckTime");
            next.setAccessible(true);
            next.setLong(stream, Long.MAX_VALUE);
        }

        /**
         * The QUERY_POLL candidate (ShardWakeupListenerDrainIT): never enters {@code processNotifies}. A trivial
         * statement's response is read up to ReadyForQuery, and every NotificationResponse in front of it lands in the
         * executor's list. Polls every {@link #QUERY_POLL_NANOS} until something arrived or the timeout passed.
         */
        private PGNotification[] queryPoll(PreparedStatement statement, BaseConnection base) throws SQLException {
            long deadline = System.nanoTime() + PGJDBC_TIMEOUT * 1_000_000L;
            while (true) {
                statement.execute();
                var ns = base.getQueryExecutor().getNotifications();
                if (ns.length > 0 || System.nanoTime() >= deadline || stop) return ns;
                LockSupport.parkNanos(QUERY_POLL_NANOS);
            }
        }

        @Override
        long cpuNanos(ThreadMXBean threads) {
            return Math.max(0, threads.getThreadCpuTime(thread.threadId()));
        }

        @Override
        String threadNames() {
            return thread.getName();
        }

        @Override
        public void close() throws Exception {
            stop = true;
            thread.join(PGJDBC_TIMEOUT * 5L);
            connection.close();
            thread.join(2000);
        }
    }

    /** A Vert.x {@code PgSubscriber}: the channel handler runs on the Netty event loop as each notification is decoded. */
    static final class VertxListener extends Listener {
        private static final Pattern URL = Pattern.compile("^jdbc:postgresql://([^/:?]+)(?::(\\d+))?/([^?]*)(\\?.*)?$");
        private final PgSubscriber   subscriber;
        private final Set<Long>      eventLoopThreads = ConcurrentHashMap.newKeySet();

        VertxListener(Pg pg, Vertx vertx, Run run) throws Exception {
            super(run);
            var m = URL.matcher(pg.jdbcUrl());
            if (!m.matches()) throw new IllegalArgumentException("cannot derive Vert.x connect options from " + pg.jdbcUrl());
            var options = new PgConnectOptions().setHost(m.group(1)).setPort(m.group(2) == null ? 5432 : Integer.parseInt(m.group(2))).setDatabase(m.group(3))
                                                .setUser(pg.server.role("listener")).setPassword(pg.server.password("listener"))
                                                .setSslMode(SslMode.DISABLE);
            subscriber = PgSubscriber.subscriber(vertx, options);
            var subscribed = new CompletableFuture<Void>();
            subscriber.channel(CHANNEL)
                      .subscribeHandler(v -> subscribed.complete(null))
                      .exceptionHandler(e -> failure = e)
                      .handler(payload -> {
                          long now = System.nanoTime();
                          eventLoopThreads.add(Thread.currentThread().threadId());
                          var r = this.run;
                          r.read(1);
                          r.received(payload, now);
                      });
            subscriber.connect().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            subscribed.get(30, TimeUnit.SECONDS);
        }

        /** The event-loop threads the handler ran on; with one subscriber connection that is one thread. */
        @Override
        long cpuNanos(ThreadMXBean threads) {
            long sum = 0;
            for (var id : eventLoopThreads) sum += Math.max(0, threads.getThreadCpuTime(id));
            return sum;
        }

        @Override
        String threadNames() {
            var names = new TreeSet<String>();
            for (var t : Thread.getAllStackTraces().keySet()) if (eventLoopThreads.contains(t.threadId())) names.add(t.getName());
            return names.toString();
        }

        @Override
        public void close() throws Exception {
            subscriber.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** One Vert.x instance for the whole run. */
    static final class VertxHolder implements AutoCloseable {
        final Vertx vertx = Vertx.vertx();

        @Override
        public void close() throws Exception {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    static final class VertxVersion {
        static String get() {
            var v = Vertx.class.getPackage().getImplementationVersion();
            return v == null ? "?" : v;
        }
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
