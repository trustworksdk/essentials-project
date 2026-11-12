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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.Stack;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SingleStatementAppendSpikeIT.Writer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.*;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/**
 * Spike P0.10 of {@code docs/plans/event-store-subscription-redesign.md}: what does the tier-1 wake-up notification
 * (§11) cost the append path?
 * <p>
 * §11 as written issues {@code pg_notify('essentials_eventstore', '<table>')} inside the appending transaction. The
 * shard-owned queue measured that a notifying commit takes PostgreSQL's cluster-wide NOTIFY lock ({@code pg_locks}
 * object, classid 1262, objid 0) and holds it through its WAL flush, so notifying commits serialise and lose group
 * commit ({@code docs/design/durable-queue-measurements.md} §3.14). This spike measures the same question on the event
 * store's append path. Arms:
 * <ul>
 *     <li>{@code NONE}: no notification;</li>
 *     <li>{@code IN_TX}: one {@code SELECT pg_notify(...)} inside the append's unit of work, after the append, before
 *     commit (one extra statement and round trip; the plan's engine could fold it into the append statement);</li>
 *     <li>{@code AFTER_COMMIT}: after commit the writer hands the payload to a per-JVM notifier, which sends whatever
 *     accumulated while its previous send was in flight as one {@code pg_notify … FROM unnest(?)} on its own
 *     connection (the queue's {@code ShardWakeupNotifier} pattern, copied here);</li>
 *     <li>{@code AFTER_COMMIT_DIRECT}: after commit each writer sends its own {@code pg_notify} as a separate autocommit
 *     statement on a pooled connection, no coalescing.</li>
 * </ul>
 * One {@code LISTEN} connection drains notifications in every arm, counting what it receives. Wake latency is
 * sampled: about every 5 ms one successful append is tagged (payload {@code <table>|s<n>} instead of {@code <table>})
 * and the time from its commit returning in the writer to the listener receiving the tagged notification is recorded.
 * <p>
 * Append shape: the current engine ({@code PostgresqlEventStore.appendToStream} in an
 * {@code EventStoreManagedUnitOfWorkFactory} unit of work) on tables created by
 * {@code SeparateTablePerAggregateTypePersistenceStrategy}, as P0.0/P0.4 arm A. Shapes ({@code -Dspike.notify.shapes},
 * default {@code 1,8,20,8@10}): {@code N} writers each appending one event to a new aggregate per unit of work with
 * expected order {@code NO_EVENTS_PREVIOUSLY_PERSISTED}; {@code N@A} writers on {@code A} pre-created aggregates, each
 * unit of work reading the last event order and appending one event after it (conflicts counted, not notified).
 * <p>
 * Per shape the arms run in a palindrome ({@code NONE, IN_TX, AFTER_COMMIT, AFTER_COMMIT_DIRECT} and back), each
 * window on a fresh table with a 1 s warm-up on that table first; the table is dropped and a {@code CHECKPOINT} taken
 * after each window. {@code pg_stat_activity} is sampled every ~2 ms for the writer and notifier roles, and every
 * tenth sample {@code pg_locks} is read for waiters on the NOTIFY lock. Passes: in a container one with
 * {@code fsync=off} and one with {@code fsync=on} ({@code -Dspike.notify.fsync=off,on}); on an external server one
 * pass with the server's own settings. {@code synchronous_commit} is the default ({@code on}) and is recorded.
 * Knobs: {@code -Dspike.notify.seconds} (default 8), {@code -Dspike.notify.arms}. Results go to
 * {@code target/spike-results/p0-10-notify.txt}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class WakeupNotifyCostSpikeIT {
    private static final String RESULTS        = "p0-10-notify.txt";
    private static final String CHANNEL        = "essentials_eventstore";
    private static final int    SECONDS        = Integer.getInteger("spike.notify.seconds", 8);
    private static final int    WARMUP_MILLIS  = Integer.getInteger("spike.notify.warmupMillis", 1000);
    private static final long   SAMPLE_EVERY_NS = Long.getLong("spike.notify.sampleEveryMicros", 5000) * 1000;
    private static final int    POOL_SIZE      = 24;
    /**
     * {@code -Dspike.notify.listener=block} drains with pgjdbc's {@code getNotifications(50)} (the default, as the queue's {@code ShardWakeupListener}). Under continuous
     * notification traffic that call does not return until the stream pauses (after the first notification pgjdbc keeps
     * reading while bytes are pending), so it cannot time individual notifications. {@code poll} sends
     * {@code SELECT 1} and takes {@code getNotifications()}' buffered notifications after each round trip, parking
     * 100 µs when there were none; that did not help either (the server answers only once its notify interrupt loop
     * has run dry). Either way the listener connection's socket is tapped ({@link NotifyTapSocketFactory}), so the
     * wire arrival of every notification is recorded too: that is the wake latency reported as "wire".
     */
    private static final boolean BLOCKING_LISTENER = "block".equals(System.getProperty("spike.notify.listener", "block"));

    enum Arm {NONE, IN_TX, AFTER_COMMIT, AFTER_COMMIT_DIRECT}

    record Shape(int writers, int aggregates) {
        static Shape parse(String s) {
            var parts = s.trim().split("@");
            return new Shape(Integer.parseInt(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
        }

        boolean contended() {
            return aggregates > 0;
        }

        @Override
        public String toString() {
            return writers + (contended() ? " writers on " + aggregates + " aggregates" : " writers, new aggregates");
        }
    }

    @Test
    void notify_cost() throws Exception {
        var shapes = Arrays.stream(System.getProperty("spike.notify.shapes", "1,8,20,8@10").split(",")).map(Shape::parse).toList();
        var arms   = Arrays.stream(System.getProperty("spike.notify.arms", "NONE,IN_TX,AFTER_COMMIT,AFTER_COMMIT_DIRECT").split(",")).map(String::trim).map(Arm::valueOf).toList();
        var passes = SpikeServer.external() ? List.of("server") : Arrays.stream(System.getProperty("spike.notify.fsync", "off,on").split(",")).map(String::trim).toList();
        for (var pass : passes) {
            try (var pg = Pg.start("on".equals(pass))) {
                for (var role : List.of("notifier", "listener")) pg.server.createRole(pg.observer, role);
                record("");
                record("=== NOTIFY | " + Instant.now() + " | " + SpikeServer.target("postgres:17.5 (P0.0 container) started with fsync=" + pass) + " | settings "
                               + settings(pg) + " | CPUs " + Runtime.getRuntime().availableProcessors() + " | shapes " + shapes + " | arms " + arms + " palindrome per shape | "
                               + SECONDS + " s windows after a " + WARMUP_MILLIS + " ms warm-up on the same fresh table | engine append (PostgresqlEventStore.appendToStream), "
                               + "Hikari pool " + POOL_SIZE + " | listener " + (BLOCKING_LISTENER ? "blocking getNotifications(50)" : "polling SELECT 1 + getNotifications()") + " | pg_stat_activity every ~2 ms, pg_locks every ~20 ms | wake latency sampled every "
                               + SAMPLE_EVERY_NS / 1000 + " µs");
                var w = new Writer(Stack.writer(pg, "writer"));
                w.stack.dataSource.setMaximumPoolSize(POOL_SIZE);
                try (var listener = new Listener(pg)) {
                    for (var shape : shapes) {
                        var order = new ArrayList<>(arms);
                        var back  = new ArrayList<>(arms);
                        Collections.reverse(back);
                        order.addAll(back);
                        for (var arm : order) window(pg, w, listener, shape, arm, pass);
                    }
                } finally {
                    w.stack.close();
                }
            }
        }
    }

    private static String settings(Pg pg) throws SQLException {
        var sb = new StringJoiner(", ");
        for (var name : List.of("fsync", "synchronous_commit", "wal_sync_method", "full_page_writes", "max_connections", "server_version")) {
            sb.add(name + "=" + SpikePostgres.queryString(pg.observer, "SELECT current_setting('" + name + "')"));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ one window

    /** Per-thread accumulators. */
    static final class Acc {
        final Lat  uow    = new Lat();
        final Lat  commit = new Lat();
        final Lat  direct = new Lat();
        long       commits, conflicts, others;
        Throwable  firstOther;

        void add(Acc o) {
            uow.add(o.uow);
            commit.add(o.commit);
            direct.add(o.direct);
            commits += o.commits;
            conflicts += o.conflicts;
            others += o.others;
            if (firstOther == null) firstOther = o.firstOther;
        }
    }

    /** A growable array of nanosecond latencies. */
    static final class Lat {
        long[] a = new long[1 << 14];
        int    n;

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        void add(Lat o) {
            for (int i = 0; i < o.n; i++) add(o.a[i]);
        }

        String summary() {
            if (n == 0) return "-";
            var s = Arrays.copyOf(a, n);
            Arrays.sort(s);
            return String.format("p50 %.3f p99 %.3f max %.1f ms", s[n / 2] / 1e6, s[Math.min(n - 1, (int) (n * 0.99))] / 1e6, s[n - 1] / 1e6);
        }

        double p(double q) {
            if (n == 0) return Double.NaN;
            var s = Arrays.copyOf(a, n);
            Arrays.sort(s);
            return s[Math.min(n - 1, (int) (n * q))] / 1e6;
        }
    }

    private void window(Pg pg, Writer w, Listener listener, Shape shape, Arm arm, String pass) throws Exception {
        var type  = w.freshType("nt");
        var cfg   = w.config(type);
        var table = cfg.eventStreamTableName;
        var aggregates = new ArrayList<OrderId>();
        for (int i = 0; i < shape.aggregates(); i++) {
            var id = OrderId.random();
            w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> w.stack.eventStore.appendToStream(type, id, Optional.empty(), List.of(new OrderEvent.OrderAdded(id, CustomerId.random(), 1))));
            aggregates.add(id);
        }
        try (var notifier = arm == Arm.AFTER_COMMIT ? new Notifier(pg) : null) {
            // warm-up on the same table, then let the notifications drain before counting
            runWriters(w, type, table, shape, aggregates, arm, notifier, Duration.ofMillis(WARMUP_MILLIS), null);
            if (notifier != null) notifier.awaitIdle();
            Thread.sleep(200);
            listener.reset(table);
            if (notifier != null) notifier.resetCounters();
            var samples = new Samples();

            var osBean   = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long jvm0    = osBean.getProcessCpuTime();
            long[] mach0 = machineCpu();
            long server0 = pg.server.cpuMicros();
            var stop     = new AtomicBoolean();
            var sampler  = new Sampler(pg, stop);
            var samplerThread = Thread.ofPlatform().name("sampler").start(sampler);

            long wall0 = System.nanoTime();
            var total  = runWriters(w, type, table, shape, aggregates, arm, notifier, Duration.ofSeconds(SECONDS), samples);
            long wallNanos = System.nanoTime() - wall0;
            double serverCpu = SpikeServer.cpuDelta(server0, pg.server.cpuMicros());
            long jvmCpu  = osBean.getProcessCpuTime() - jvm0;
            long[] mach1 = machineCpu();
            stop.set(true);
            samplerThread.join();

            if (notifier != null) notifier.awaitIdle();
            // drain: wait until the listener has stopped receiving
            long received = -1;
            for (int i = 0; i < 50 && received != listener.received.get(); i++) {
                received = listener.received.get();
                Thread.sleep(100);
            }

            double seconds = wallNanos / 1e9;
            long sent = switch (arm) {
                case NONE -> 0;
                case IN_TX, AFTER_COMMIT_DIRECT -> total.commits;
                case AFTER_COMMIT -> notifier.keysSent.sum();
            };
            var wake = samples.wakeLatencies(listener.sampleWireAt);
            var api  = samples.wakeLatencies(listener.sampleReceivedAt);
            double machineBusy = (double) (mach1[0] - mach0[0]) / Math.max(1, mach1[1] - mach0[1]);
            record(String.format("NOTIFY fsync=%s | %-28s | %-19s | appended %7d (%6.0f/s), conflicts %d, other %d | unit of work %s | commit %s",
                                 pass, shape, arm, total.commits, total.commits / seconds, total.conflicts, total.others, total.uow.summary(), total.commit.summary()));
            record(String.format("  notifications: sent %d%s, received %d%s | wake latency, commit returned -> on the listener's socket (wire) n=%d/%d sampled: %s; -> returned by pgjdbc: %s%s",
                                 sent,
                                 arm == Arm.AFTER_COMMIT ? String.format(" in %d batches (%.1f keys per batch, %.2f batches per append), batch failures %d",
                                                                          notifier.batchesSent.sum(), notifier.keysSent.sum() / (double) Math.max(1, notifier.batchesSent.sum()),
                                                                          notifier.batchesSent.sum() / (double) Math.max(1, total.commits), notifier.batchFailures.sum()) : "",
                                 listener.received.get(), (listener.foreign.get() > 0 ? " (+" + listener.foreign.get() + " for other tables)" : "")
                                         + " in " + listener.deliveries.get() + " listener reads (at most " + listener.maxBatch.get() + " per read)",
                                 wake.n, samples.count(), wake.summary(), api.summary(),
                                 arm == Arm.AFTER_COMMIT_DIRECT ? " | writer's after-commit notify statement " + total.direct.summary() : ""));
            record(String.format("  PostgreSQL CPU %.1f µs per append (%.2f cores) | JVM CPU %.1f µs per append | machine busy %.0f%% of %d CPUs",
                                 serverCpu / Math.max(1, total.commits), serverCpu / 1e6 / seconds, jvmCpu / 1000.0 / Math.max(1, total.commits), 100 * machineBusy,
                                 Runtime.getRuntime().availableProcessors()));
            record("  " + sampler.summary());
            record(String.format("CSV,%s,%s,%d,%d,%s,%.0f,%.3f,%.3f,%.3f,%.3f,%d,%d,%.3f,%.3f,%.1f,%s",
                                 pass, shape.contended() ? "contended" : "distinct", shape.writers(), shape.aggregates(), arm, total.commits / seconds,
                                 total.uow.p(0.5), total.uow.p(0.99), total.commit.p(0.5), total.commit.p(0.99), sent, listener.received.get(),
                                 wake.p(0.5), wake.p(0.99), serverCpu / Math.max(1, total.commits), sampler.compact()));
            if (total.firstOther != null) record("  first other error: " + total.firstOther);
        } finally {
            try (var s = pg.observer.createStatement()) {
                s.execute("DROP TABLE " + table);
                s.execute("CHECKPOINT");
            }
        }
    }

    private Acc runWriters(Writer w, AggregateType type, String table, Shape shape, List<OrderId> aggregates, Arm arm, Notifier notifier, Duration duration, Samples samples) throws Exception {
        long stopAt = System.nanoTime() + duration.toNanos();
        var pool    = Executors.newFixedThreadPool(shape.writers());
        var futures = new ArrayList<Future<Acc>>();
        for (int t = 0; t < shape.writers(); t++) {
            futures.add(pool.submit(() -> {
                var acc    = new Acc();
                var random = ThreadLocalRandom.current();
                var bodyEnd = new long[1];
                while (System.nanoTime() < stopAt) {
                    long sampleId = samples == null ? -1 : samples.claim();
                    var payload   = sampleId < 0 ? table : table + "|s" + sampleId;
                    long t0 = System.nanoTime();
                    try {
                        w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
                            if (shape.contended()) {
                                var id   = aggregates.get(random.nextInt(aggregates.size()));
                                long last = w.stack.eventStore.loadLastPersistedEventRelatedTo(type, id).orElseThrow().eventOrder().longValue();
                                w.stack.eventStore.appendToStream(type, id, Optional.of(last), List.of(new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 1)));
                            } else {
                                var id = OrderId.random();
                                w.stack.eventStore.appendToStream(type, id, Optional.of(EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED.longValue()),
                                                                  List.of(new OrderEvent.OrderAdded(id, CustomerId.random(), 1)));
                            }
                            if (arm == Arm.IN_TX) {
                                try (var ps = uow.handle().getConnection().prepareStatement("SELECT pg_notify(?, ?)")) {
                                    ps.setString(1, CHANNEL);
                                    ps.setString(2, payload);
                                    ps.executeQuery().close();
                                }
                            }
                            bodyEnd[0] = System.nanoTime();
                        });
                    } catch (Throwable e) {
                        if (isConflict(e)) acc.conflicts++;
                        else {
                            acc.others++;
                            if (acc.firstOther == null) acc.firstOther = e;
                        }
                        if (sampleId >= 0) samples.cancel(sampleId);
                        continue;
                    }
                    long t1 = System.nanoTime();
                    acc.commits++;
                    acc.uow.add(t1 - t0);
                    acc.commit.add(t1 - bodyEnd[0]);
                    if (sampleId >= 0) samples.committed(sampleId, t1);
                    switch (arm) {
                        case AFTER_COMMIT -> notifier.submit(payload);
                        case AFTER_COMMIT_DIRECT -> {
                            long n0 = System.nanoTime();
                            try (var c = w.stack.dataSource.getConnection(); var ps = c.prepareStatement("SELECT pg_notify(?, ?)")) {
                                ps.setString(1, CHANNEL);
                                ps.setString(2, payload);
                                ps.executeQuery().close();
                            }
                            acc.direct.add(System.nanoTime() - n0);
                        }
                        default -> {
                        }
                    }
                }
                return acc;
            }));
        }
        pool.shutdown();
        var total = new Acc();
        for (var f : futures) total.add(f.get(duration.toSeconds() + 120, TimeUnit.SECONDS));
        return total;
    }

    private static boolean isConflict(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c.getClass().getSimpleName().contains("OptimisticAppendToStream")) return true;
            if (c instanceof SQLException s && "23505".equals(s.getSQLState())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ wake latency samples

    /** About one tagged append per {@link #SAMPLE_EVERY_NS}, across all writers. */
    static final class Samples {
        private final AtomicLong                 nextAt    = new AtomicLong(System.nanoTime());
        private final AtomicLong                 ids       = new AtomicLong();
        private final ConcurrentHashMap<Long, Long> committed = new ConcurrentHashMap<>();

        long claim() {
            long now = System.nanoTime(), at = nextAt.get();
            if (now >= at && nextAt.compareAndSet(at, now + SAMPLE_EVERY_NS)) return ids.incrementAndGet();
            return -1;
        }

        void cancel(long id) {
            // a conflicting attempt is rolled back and sends nothing; just not counted
        }

        void committed(long id, long nanos) {
            committed.put(id, nanos);
        }

        int count() {
            return committed.size();
        }

        Lat wakeLatencies(Map<Long, Long> receivedAt) {
            var lat = new Lat();
            committed.forEach((id, at) -> {
                var rx = receivedAt.get(id);
                if (rx != null) lat.add(rx - at);
            });
            return lat;
        }
    }

    // ------------------------------------------------------------------ LISTEN

    /** One LISTEN connection, draining notifications on its own thread. */
    static final class Listener implements AutoCloseable {
        final AtomicLong                          received         = new AtomicLong();
        final AtomicLong                          foreign          = new AtomicLong();
        /** Calls that returned at least one notification, and the most one call returned. */
        final AtomicLong                          deliveries       = new AtomicLong();
        final AtomicLong                          maxBatch         = new AtomicLong();
        final ConcurrentHashMap<Long, Long>       sampleReceivedAt = new ConcurrentHashMap<>();
        /** When the tagged notification's bytes reached the listener's socket. */
        final ConcurrentHashMap<Long, Long>       sampleWireAt     = new ConcurrentHashMap<>();
        private final Connection                  connection;
        private final Thread                      thread;
        private volatile String                   table = "";
        private volatile boolean                  stop;

        Listener(Pg pg) throws SQLException {
            var url = pg.jdbcUrl();
            url = url + (url.contains("?") ? "&" : "?") + "sslmode=disable&socketFactory=" + NotifyTapSocketFactory.class.getName();
            NotifyTapSocketFactory.sink = tap -> {
                var p   = tap.payload();
                int bar = p.indexOf('|');
                if (bar >= 0 && p.substring(0, bar).equals(table)) sampleWireAt.put(Long.parseLong(p.substring(bar + 2)), tap.nanos());
            };
            connection = DriverManager.getConnection(url, pg.server.role("listener"), pg.server.password("listener"));
            try (var s = connection.createStatement()) {
                s.execute("LISTEN " + CHANNEL);
            }
            var pgc  = connection.unwrap(PGConnection.class);
            var poll = connection.prepareStatement("SELECT 1");
            thread = Thread.ofPlatform().name("listener").start(() -> {
                while (!stop) {
                    try {
                        PGNotification[] ns;
                        if (BLOCKING_LISTENER) {
                            ns = pgc.getNotifications(50);
                        } else {
                            poll.executeQuery().close();
                            ns = pgc.getNotifications();
                        }
                        if (ns == null || ns.length == 0) {
                            if (!BLOCKING_LISTENER) LockSupport.parkNanos(100_000);
                            continue;
                        }
                        long now = System.nanoTime();
                        deliveries.incrementAndGet();
                        maxBatch.accumulateAndGet(ns.length, Math::max);
                        for (var n : ns) {
                            var p   = n.getParameter();
                            int bar = p.indexOf('|');
                            var t   = bar < 0 ? p : p.substring(0, bar);
                            if (!t.equals(table)) {
                                foreign.incrementAndGet();
                                continue;
                            }
                            received.incrementAndGet();
                            if (bar >= 0) sampleReceivedAt.put(Long.parseLong(p.substring(bar + 2)), now);
                        }
                    } catch (SQLException e) {
                        if (!stop) System.err.println("SPIKE listener: " + e);
                        return;
                    }
                }
            });
        }

        void reset(String table) {
            this.table = table;
            received.set(0);
            foreign.set(0);
            deliveries.set(0);
            maxBatch.set(0);
            sampleReceivedAt.clear();
            sampleWireAt.clear();
        }

        @Override
        public void close() throws Exception {
            stop = true;
            thread.join(2000);
            connection.close();
        }
    }

    // ------------------------------------------------------------------ after-commit notifier (the queue's pattern)

    /**
     * A copy of the shard-owned queue's {@code ShardWakeupNotifier} send loop: payloads are queued, and one thread sends
     * whatever accumulated while its previous send was in flight as a single {@code pg_notify … FROM unnest(?)}
     * statement on its own autocommit connection.
     */
    static final class Notifier implements AutoCloseable {
        final LongAdder          keysSent      = new LongAdder();
        final LongAdder          batchesSent   = new LongAdder();
        final LongAdder          batchFailures = new LongAdder();
        private final Connection connection;
        private final Object     lock          = new Object();
        private Set<String>      pending       = new LinkedHashSet<>();
        private boolean          sending;
        private boolean          closed;
        private final Thread     thread;

        Notifier(Pg pg) throws SQLException {
            connection = DriverManager.getConnection(pg.jdbcUrl(), pg.server.role("notifier"), pg.server.password("notifier"));
            thread = Thread.ofPlatform().name("notifier").start(this::run);
        }

        void submit(String key) {
            synchronized (lock) {
                pending.add(key);
                lock.notifyAll();
            }
        }

        private void run() {
            try (var ps = connection.prepareStatement("SELECT pg_notify(?, key) FROM unnest(?::text[]) AS key")) {
                while (true) {
                    Set<String> batch;
                    synchronized (lock) {
                        sending = false;
                        lock.notifyAll();
                        while (pending.isEmpty() && !closed) lock.wait();
                        if (pending.isEmpty()) return;
                        batch = pending;
                        pending = new LinkedHashSet<>();
                        sending = true;
                    }
                    try {
                        ps.setString(1, CHANNEL);
                        ps.setArray(2, connection.createArrayOf("text", batch.toArray()));
                        ps.executeQuery().close();
                        keysSent.add(batch.size());
                        batchesSent.increment();
                    } catch (SQLException e) {
                        batchFailures.increment();
                    }
                }
            } catch (SQLException | InterruptedException e) {
                System.err.println("SPIKE notifier: " + e);
            }
        }

        void awaitIdle() throws InterruptedException {
            synchronized (lock) {
                while (sending || !pending.isEmpty()) lock.wait(100);
            }
        }

        void resetCounters() {
            keysSent.reset();
            batchesSent.reset();
            batchFailures.reset();
        }

        @Override
        public void close() throws Exception {
            synchronized (lock) {
                closed = true;
                lock.notifyAll();
            }
            thread.join(5000);
            connection.close();
        }
    }

    // ------------------------------------------------------------------ wait-event sampler

    /** Samples the writer and notifier backends from pg_stat_activity, and NOTIFY-lock waiters from pg_locks. */
    static final class Sampler implements Runnable {
        private final Pg                pg;
        private final AtomicBoolean     stop;
        private final Map<String, Long> buckets = new TreeMap<>();
        private final Map<String, Long> activeByRole = new TreeMap<>();
        private long                    samples, lockSamples, lockSamplesWithWaiter, lockWaiters;

        Sampler(Pg pg, AtomicBoolean stop) {
            this.pg = pg;
            this.stop = stop;
        }

        @Override
        public void run() {
            var writer   = pg.server.role("writer");
            var notifier = pg.server.role("notifier");
            try (var c = DriverManager.getConnection(pg.server.getJdbcUrl(), pg.server.getUsername(), pg.server.getPassword());
                 var s = c.prepareStatement("SELECT usename, state, coalesce(wait_event_type, 'CPU'), coalesce(wait_event, '-') FROM pg_stat_activity WHERE usename IN ('"
                                                    + writer + "', '" + notifier + "')");
                 var l = c.prepareStatement("SELECT count(*) FROM pg_locks WHERE locktype = 'object' AND classid = 1262 AND objid = 0 AND NOT granted")) {
                while (!stop.get()) {
                    try (var rs = s.executeQuery()) {
                        while (rs.next()) {
                            if (!"active".equals(rs.getString(2))) continue;
                            var role = rs.getString(1).equals(writer) ? "writer" : "notifier";
                            buckets.merge(role + " " + rs.getString(3) + ":" + rs.getString(4), 1L, Long::sum);
                            activeByRole.merge(role, 1L, Long::sum);
                        }
                    }
                    if (samples % 10 == 0) {
                        long waiting;
                        try (var rs = l.executeQuery()) {
                            rs.next();
                            waiting = rs.getLong(1);
                        }
                        lockSamples++;
                        if (waiting > 0) lockSamplesWithWaiter++;
                        lockWaiters += waiting;
                    }
                    samples++;
                    LockSupport.parkNanos(2_000_000);
                }
            } catch (SQLException e) {
                buckets.merge("sampler failed: " + e.getMessage(), 1L, Long::sum);
            }
        }

        private long active(String role) {
            return activeByRole.getOrDefault(role, 0L);
        }

        /** Shares of active backend-samples per role, largest first. */
        String summary() {
            var sb = new StringBuilder("wait events, share of active backend-samples (" + samples + " samples; active writer backends per sample "
                                               + String.format("%.2f", active("writer") / (double) Math.max(1, samples)) + "):");
            for (var role : List.of("writer", "notifier")) {
                if (active(role) == 0) continue;
                var j = new StringJoiner(", ", " " + role + " [", "]");
                buckets.entrySet().stream().filter(e -> e.getKey().startsWith(role + " "))
                       .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                       .limit(8)
                       .forEach(e -> j.add(String.format("%s %.1f%%", e.getKey().substring(role.length() + 1), 100.0 * e.getValue() / active(role))));
                sb.append(j);
            }
            sb.append(String.format(" | NOTIFY lock (pg_locks object 1262/0) waiters: in %.1f%% of %d lock samples, %.2f waiting on average",
                                    100.0 * lockSamplesWithWaiter / Math.max(1, lockSamples), lockSamples, lockWaiters / (double) Math.max(1, lockSamples)));
            return sb.toString();
        }

        /** writer Lock:object share, writer LWLock:WALWrite share, writer IO:WalSync + IO:WalWrite share, writer CPU share (percent). */
        String compact() {
            double a = Math.max(1, active("writer"));
            return String.format("%.1f,%.1f,%.1f,%.1f,%.1f",
                                 100 * buckets.getOrDefault("writer Lock:object", 0L) / a,
                                 100 * buckets.getOrDefault("writer LWLock:WALWrite", 0L) / a,
                                 100 * (buckets.getOrDefault("writer IO:WalSync", 0L) + buckets.getOrDefault("writer IO:WalWrite", 0L)) / a,
                                 100 * buckets.getOrDefault("writer CPU:-", 0L) / a,
                                 100.0 * lockSamplesWithWaiter / Math.max(1, lockSamples));
        }
    }

    /** {busy jiffies, total jiffies} of the whole machine. */
    private static long[] machineCpu() {
        try {
            var f = Files.readAllLines(Path.of("/proc/stat")).getFirst().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);
            return new long[]{total - idle, total};
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
