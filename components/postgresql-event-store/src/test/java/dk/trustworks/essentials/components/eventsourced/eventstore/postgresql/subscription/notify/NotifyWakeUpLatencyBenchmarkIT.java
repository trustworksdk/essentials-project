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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.reactive.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.Disposable;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;

/**
 * Opt-in A/B benchmark for S2 (interruptible wake-up) in {@code docs/archive/subscription-improvements.md}. Asserts nothing,
 * prints its numbers and writes them to {@code target/notify-wake-up-benchmark.txt}.
 * <p>
 * Workload: {@code benchmark.wakeup.aggregate-types} aggregate types, each appended to with one single-event transaction
 * at a fixed rate (0.1 Hz and 1 Hz, each type with its own random phase) and polled by 2 {@link PostgresqlEventStore}
 * polling subscriptions. Arms, alternated round by round for {@code benchmark.wakeup.rounds} rounds:
 * <ul>
 *   <li>{@code plain} - the Spring starter's default optimizer without notify-polling: jittered, 100 ms, backing off to 2 s</li>
 *   <li>{@code phase1-1s} - notify-polling Phase 1, {@code max-delay=1s}</li>
 *   <li>{@code s2-5s} - S2 interruptible wait, {@code max-delay=5s}, listener polling every 50 ms (the Spring default)</li>
 *   <li>{@code s2-5s-10ms} - the same with the listener polling every 10 ms</li>
 * </ul>
 * Measured per arm: wake-up latency (append committed to subscriber handler, p50/p95/p99 over every delivered event of
 * every round), polls per second per subscriber (from the optimizer) and index+sequential scans of the event-stream
 * tables per second per subscriber ({@code pg_stat_user_tables}, which also counts the appends' own reads - same in
 * every arm). Every run starts with an unmeasured idle warm-up so the backoff has reached its steady state.
 * <p>
 * Burst: arm {@code s2-5s} at 1 Hz while {@code benchmark.wakeup.burst-appenders} appenders commit single-event
 * transactions as fast as they can to another aggregate type, with an asynchronous subscriber that takes 1 ms per event
 * on the listener's bus.
 * <p>
 * {@code mvn verify -pl components/postgresql-event-store -am -DskipUTs -Dbenchmark.run=true
 * -Dit.test=NotifyWakeUpLatencyBenchmarkIT -Dfailsafe.failIfNoSpecifiedTests=false}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class NotifyWakeUpLatencyBenchmarkIT {
    private static final Logger        log             = LoggerFactory.getLogger(NotifyWakeUpLatencyBenchmarkIT.class);
    private static final int           ROUNDS          = Integer.getInteger("benchmark.wakeup.rounds", 3);
    private static final int           TYPES           = Integer.getInteger("benchmark.wakeup.aggregate-types", 4);
    private static final int           SLOW_SECONDS    = Integer.getInteger("benchmark.wakeup.slow-seconds", 40);
    private static final int           FAST_SECONDS    = Integer.getInteger("benchmark.wakeup.fast-seconds", 15);
    private static final int           WARMUP_SECONDS  = Integer.getInteger("benchmark.wakeup.warmup-seconds", 7);
    private static final int           BURST_APPENDERS = Integer.getInteger("benchmark.wakeup.burst-appenders", 8);
    private static final int           BURST_SECONDS   = Integer.getInteger("benchmark.wakeup.burst-seconds", 15);
    private static final int           BURST_ROUNDS    = Integer.getInteger("benchmark.wakeup.burst-rounds", 3);
    private static final boolean       BURST_ONLY      = Boolean.getBoolean("benchmark.wakeup.burst-only");
    private static final int           SUBSCRIBERS     = 2;
    private static final AggregateType BURST           = AggregateType.of("BurstOrders");

    @Container
    private static final PostgreSQLContainer postgres = EssentialsTestContainers.postgres("notify-wake-up-benchmark");

    private static HikariDataSource                                                        dataSource;
    private static Jdbi                                                                    jdbi;
    private static EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                        unitOfWorkFactory;
    private static PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private static final Set<String>                                                       eventStreamTables = ConcurrentHashMap.newKeySet();
    private static final List<AggregateType>                                               types             = new ArrayList<>();
    private static final StringBuilder                                                     report            = new StringBuilder();

    enum Arm {
        PLAIN("plain", 50, false), PHASE1_1S("phase1-1s", 50, true), S2_5S("s2-5s", 50, true), S2_5S_10MS("s2-5s-10ms", 10, true);

        final String  label;
        final long    listenerIntervalMs;
        final boolean notify;

        Arm(String label, long listenerIntervalMs, boolean notify) {
            this.label = label;
            this.listenerIntervalMs = listenerIntervalMs;
            this.notify = notify;
        }
    }

    @BeforeAll
    static void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(TYPES * SUBSCRIBERS + TYPES + BURST_APPENDERS + 6);
        dataSource = new HikariDataSource(config);
        jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new PostgresPlugin());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                                  .setJdbi(jdbi)
                                                                                  .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                  .setEventMapper((aggregateId, configuration, event, eventOrder) ->
                                                                                                          PersistableEvent.builder()
                                                                                                                          .setEvent(event)
                                                                                                                          .setAggregateType(configuration.aggregateType)
                                                                                                                          .setAggregateId(aggregateId)
                                                                                                                          .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                                                                                                                          .setEventOrder(eventOrder)
                                                                                                                          .build())
                                                                                  .setAggregateEventStreamConfigurationFactory(standardSingleTenantConfiguration(
                                                                                          EssentialsJSONEventSerializers.create(),
                                                                                          IdentifierColumnType.TEXT,
                                                                                          JSONColumnType.JSONB))
                                                                                  .build();
        // The trigger is part of every table's schema; each arm registers the tables with its own listener
        persistenceStrategy.enableNotifyTriggers(eventStreamTables::add);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        for (int i = 0; i < TYPES; i++) {
            var type = AggregateType.of("WakeUpOrders" + i);
            types.add(type);
            eventStore.addAggregateEventStreamConfiguration(type, AggregateIdSerializer.serializerFor(String.class));
        }
        eventStore.addAggregateEventStreamConfiguration(BURST, AggregateIdSerializer.serializerFor(String.class));

        var version = jdbi.withHandle(h -> h.createQuery("SELECT version()").mapTo(String.class).one());
        line("Environment: %d CPUs visible to the JVM, %s, JDK %s", Runtime.getRuntime().availableProcessors(), version, System.getProperty("java.version"));
        line("Settings: rounds=%d, aggregate types=%d x %d subscribers, 0.1 Hz runs %ds, 1 Hz runs %ds, idle warm-up %ds per run",
             ROUNDS, TYPES, SUBSCRIBERS, SLOW_SECONDS, FAST_SECONDS, WARMUP_SECONDS);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (dataSource != null) dataSource.close();
        var out = Path.of("target", "notify-wake-up-benchmark.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        log.info("Benchmark report written to {}", out.toAbsolutePath());
    }

    @Test
    void wake_up_latency_and_poll_rate_per_arm() throws Exception {
        if (BURST_ONLY) {
            burstRounds();
            return;
        }
        for (double hz : new double[]{0.1, 1.0}) {
            int  seconds = hz < 0.5 ? SLOW_SECONDS : FAST_SECONDS;
            var  results = new EnumMap<Arm, List<RunResult>>(Arm.class);
            line("");
            line("== %.1f Hz per aggregate type, %d s per run ==", hz, seconds);
            line("%-11s %-5s %8s %8s %8s %8s %12s %12s", "arm", "round", "events", "p50 ms", "p95 ms", "p99 ms", "polls/s/sub", "scans/s/sub");
            for (int round = 1; round <= ROUNDS; round++) {
                var arms = new ArrayList<>(List.of(Arm.values()));
                Collections.rotate(arms, -(round - 1)); // alternate the order between rounds
                for (var arm : arms) {
                    var result = run(arm, hz, seconds, false);
                    results.computeIfAbsent(arm, a -> new ArrayList<>()).add(result);
                    line("%-11s %-5d %8d %8.1f %8.1f %8.1f %12.2f %12.2f", arm.label, round, result.latenciesMs.length,
                         percentile(result.latenciesMs, 0.50), percentile(result.latenciesMs, 0.95), percentile(result.latenciesMs, 0.99),
                         result.pollsPerSecondPerSubscriber, result.scansPerSecondPerSubscriber);
                }
            }
            line("SUMMARY %.1f Hz (latency pooled over rounds, rates median of rounds):", hz);
            line("%-11s %8s %8s %8s %8s %12s %12s", "arm", "events", "p50 ms", "p95 ms", "p99 ms", "polls/s/sub", "scans/s/sub");
            for (var arm : Arm.values()) {
                var runs   = results.get(arm);
                var pooled = runs.stream().flatMapToDouble(r -> Arrays.stream(r.latenciesMs)).sorted().toArray();
                line("%-11s %8d %8.1f %8.1f %8.1f %12.2f %12.2f", arm.label, pooled.length,
                     percentile(pooled, 0.50), percentile(pooled, 0.95), percentile(pooled, 0.99),
                     median(runs.stream().mapToDouble(r -> r.pollsPerSecondPerSubscriber).toArray()),
                     median(runs.stream().mapToDouble(r -> r.scansPerSecondPerSubscriber).toArray()));
            }
        }

        line("");
        line("== Burst: 1 Hz workload while %d appenders flood %s for %d s, async bus subscriber taking 1 ms/event ==", BURST_APPENDERS, BURST, BURST_SECONDS);
        for (var arm : List.of(Arm.PLAIN, Arm.S2_5S)) { // plain polling shows what the saturated database alone costs
            var burst = run(arm, 1.0, BURST_SECONDS, true);
            line("%-11s events %d, p50 %.1f ms, p95 %.1f ms, p99 %.1f ms, max %.1f ms, polls/s/sub %.2f, burst tx committed %d", arm.label,
                 burst.latenciesMs.length, percentile(burst.latenciesMs, 0.50), percentile(burst.latenciesMs, 0.95), percentile(burst.latenciesMs, 0.99),
                 burst.latenciesMs.length == 0 ? 0 : burst.latenciesMs[burst.latenciesMs.length - 1], burst.pollsPerSecondPerSubscriber, burst.burstTransactions);
        }
    }

    /**
     * The burst scenario, arms alternated round by round, with a per-event breakdown of the wake-up latency: when the
     * listener delivered the quiet table's NOTIFY, when the poll that handed the event over started, and how long that poll took
     */
    private void burstRounds() throws Exception {
        line("");
        line("== Burst, %d alternated rounds: 1 Hz workload while %d appenders flood %s for %d s, async bus subscriber taking 1 ms/event ==",
             BURST_ROUNDS, BURST_APPENDERS, BURST, BURST_SECONDS);
        line("Breakdown per event: notify = listener delivered the quiet table's first NOTIFY after the commit, poll = start of the poll that handed the event over, handler = event handled; all from the commit");
        var arms    = new ArrayList<>(List.of(Arm.PLAIN, Arm.PHASE1_1S, Arm.S2_5S));
        var results = new EnumMap<Arm, List<RunResult>>(Arm.class);
        for (int round = 1; round <= BURST_ROUNDS; round++) {
            for (var arm : arms) {
                var result = run(arm, 1.0, BURST_SECONDS, true);
                results.computeIfAbsent(arm, a -> new ArrayList<>()).add(result);
                line("%-10s round %d: burst tx %d, polls/s/sub %.2f, %s", arm.label, round, result.burstTransactions, result.pollsPerSecondPerSubscriber, result.breakdown);
            }
            Collections.rotate(arms, -1);
        }
        line("SUMMARY burst (pooled over rounds):");
        for (var arm : List.of(Arm.PLAIN, Arm.PHASE1_1S, Arm.S2_5S)) {
            var samples = results.get(arm).stream().flatMap(r -> r.samples.stream()).toList();
            line("%-10s %s", arm.label, breakdown(samples));
        }
    }

    record Sample(double totalMs, double notifyMs, double pollStartMs, double pollMs) {
    }

    private static String breakdown(List<Sample> samples) {
        if (samples.isEmpty()) return "no events";
        var total = samples.stream().mapToDouble(Sample::totalMs).sorted().toArray();
        double p95  = percentile(total, 0.95);
        var tail = samples.stream().filter(x -> x.totalMs >= p95).toList();
        return String.format("events %d, total p50/p95/p99 %.1f/%.1f/%.1f ms | all: %s | tail (total >= p95, %d events): %s",
                             samples.size(), percentile(total, 0.5), p95, percentile(total, 0.99), components(samples), tail.size(), components(tail));
    }

    private static String components(List<Sample> samples) {
        var notify    = samples.stream().mapToDouble(Sample::notifyMs).filter(d -> !Double.isNaN(d)).sorted().toArray();
        var pollStart = samples.stream().mapToDouble(Sample::pollStartMs).sorted().toArray();
        var poll      = samples.stream().mapToDouble(Sample::pollMs).sorted().toArray();
        long beforeNotify = samples.stream().filter(x -> !Double.isNaN(x.notifyMs) && x.pollStartMs < x.notifyMs).count();
        return String.format("notify p50/p95 %.1f/%.1f, poll start p50/p95 %.1f/%.1f, poll duration p50/p95 %.1f/%.1f, polls started before the notify %d",
                             percentile(notify, 0.5), percentile(notify, 0.95), percentile(pollStart, 0.5), percentile(pollStart, 0.95),
                             percentile(poll, 0.5), percentile(poll, 0.95), beforeNotify);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private RunResult run(Arm arm, double hz, int seconds, boolean withBurst) throws Exception {
        var bus      = LocalEventBus.builder().busName("WakeUpBenchmark-" + arm.label).build();
        var listener = new MultiTableChangeListener<TableChangeNotification>(jdbi, Duration.ofMillis(arm.listenerIntervalMs), EssentialsObjectMappers.createJSONSerializer(), bus, true);
        listener.start();
        var filter = EventStreamTableNotificationDuplicationFilter.registerWith(listener);
        eventStreamTables.forEach(filter::listenTo);
        var epochSource = new NotifyEpochSource(bus);
        EventHandler slow = event -> LockSupport.parkNanos(1_000_000);
        if (withBurst) bus.addAsyncSubscriber(slow);

        var commitTimes   = new ConcurrentHashMap<String, Long>();
        var notifyTimes   = new ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>();
        var pollStarts    = new ConcurrentHashMap<SubscriberId, ConcurrentLinkedQueue<Long>>();
        var handled       = new ConcurrentLinkedQueue<long[]>(); // committedAt, handledAt
        var handledBy     = new ConcurrentLinkedQueue<Object[]>(); // table, subscriberId
        EventHandler notifyProbe = event -> {
            if (event instanceof TableChangeNotification notification && notification.getTableName() != null) {
                notifyTimes.computeIfAbsent(notification.getTableName(), t -> new ConcurrentLinkedQueue<>()).add(System.nanoTime());
            }
        };
        bus.addSyncSubscriber(notifyProbe);
        var latencies     = new ConcurrentLinkedQueue<Double>();
        var polls         = new AtomicLong();
        var measuring     = new AtomicBoolean(false);
        var subscriptions = new ArrayList<Disposable>();
        try {
            for (var type : types) {
                var  table = eventStore.getAggregateEventStreamConfiguration(type).eventStreamTableName;
                long head  = jdbi.withHandle(h -> h.createQuery("SELECT coalesce(max(global_order), 0) FROM " + table).mapTo(Long.class).one());
                for (int s = 0; s < SUBSCRIBERS; s++) {
                    var subscriberId = SubscriberId.of(arm.label + "-" + type + "-" + s + "-" + UUID.randomUUID());
                    var starts = pollStarts.computeIfAbsent(subscriberId, id -> new ConcurrentLinkedQueue<>());
                    Function<String, EventStorePollingOptimizer> factory = logName -> new CountingOptimizer(optimizerFor(arm, logName, epochSource, table), polls, measuring, starts);
                    subscriptions.add(eventStore.pollEvents(type, head + 1, Optional.empty(), Optional.of(Duration.ofMillis(100)), Optional.empty(), Optional.of(subscriberId), Optional.of(factory))
                                                .subscribe(event -> {
                                                    if (!measuring.get()) return;
                                                    // A poll can see the event before its appender has recorded the commit time
                                                    var  id          = (String) event.aggregateId();
                                                    long waitUntil   = System.nanoTime() + 1_000_000_000L;
                                                    var  committedAt = commitTimes.get(id);
                                                    while (committedAt == null && System.nanoTime() < waitUntil) {
                                                        LockSupport.parkNanos(20_000);
                                                        committedAt = commitTimes.get(id);
                                                    }
                                                    if (committedAt != null) {
                                                        long handledAt = System.nanoTime();
                                                        latencies.add(Math.max(0, handledAt - committedAt) / 1e6);
                                                        synchronized (handled) {
                                                            handled.add(new long[]{committedAt, handledAt});
                                                            handledBy.add(new Object[]{table, subscriberId});
                                                        }
                                                    }
                                                }));
                }
            }
            Thread.sleep(Duration.ofSeconds(WARMUP_SECONDS));

            var burstRunning      = new AtomicBoolean(withBurst);
            var burstTransactions = new AtomicLong();
            var burstThreads      = new ArrayList<Thread>();
            for (int i = 0; withBurst && i < BURST_APPENDERS; i++) {
                burstThreads.add(Thread.ofPlatform().name("burst-" + i).start(() -> {
                    while (burstRunning.get()) {
                        var id = UUID.randomUUID().toString();
                        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(BURST, id, List.of(Map.of("id", id))));
                        burstTransactions.incrementAndGet();
                    }
                }));
            }

            long scansBefore = scans();
            measuring.set(true);
            long start      = System.nanoTime();
            long end        = start + Duration.ofSeconds(seconds).toNanos();
            long periodNs   = (long) (1e9 / hz);
            var  random     = new Random(42);
            var  appenders  = new ArrayList<Thread>();
            for (var type : types) {
                long phase = (long) (random.nextDouble() * periodNs);
                appenders.add(Thread.ofPlatform().name("appender-" + type).start(() -> {
                    long next = start + phase;
                    while (next < end) {
                        LockSupport.parkNanos(next - System.nanoTime());
                        var id = UUID.randomUUID().toString();
                        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(type, id, List.of(Map.of("id", id))));
                        commitTimes.put(id, System.nanoTime());
                        next += periodNs;
                    }
                }));
            }
            for (var appender : appenders) appender.join();
            LockSupport.parkNanos(end - System.nanoTime());
            long elapsed = System.nanoTime() - start;
            measuring.set(false);
            long scansAfter = scans();
            burstRunning.set(false);
            for (var thread : burstThreads) thread.join();

            int    subscribers = types.size() * SUBSCRIBERS;
            double secondsRun  = elapsed / 1e9;
            var    sorted      = latencies.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            var samples = new ArrayList<Sample>();
            var times   = new ArrayList<>(handled);
            var by      = new ArrayList<>(handledBy);
            for (int i = 0; i < times.size(); i++) {
                long   c         = times.get(i)[0];
                long   h         = times.get(i)[1];
                var    notifies  = notifyTimes.getOrDefault((String) by.get(i)[0], new ConcurrentLinkedQueue<>()).stream().mapToLong(Long::longValue).sorted().toArray();
                var    starts    = pollStarts.get((SubscriberId) by.get(i)[1]).stream().mapToLong(Long::longValue).sorted().toArray();
                long   n         = Arrays.stream(notifies).filter(t -> t >= c).findFirst().orElse(-1);
                long   ps        = Arrays.stream(starts).filter(t -> t <= h).max().orElse(c);
                samples.add(new Sample((h - c) / 1e6, n < 0 ? Double.NaN : (n - c) / 1e6, (ps - c) / 1e6, (h - ps) / 1e6));
            }
            return new RunResult(sorted, polls.get() / secondsRun / subscribers, (scansAfter - scansBefore) / secondsRun / subscribers, burstTransactions.get(), samples, breakdown(samples));
        } finally {
            subscriptions.forEach(Disposable::dispose);
            if (withBurst) bus.removeAsyncSubscriber(slow);
            bus.removeSyncSubscriber(notifyProbe);
            epochSource.close();
            listener.stop();
            Thread.sleep(500);
        }
    }

    private static EventStorePollingOptimizer optimizerFor(Arm arm, String logName, NotifyEpochSource epochSource, String table) {
        return switch (arm) {
            case PLAIN -> new JitteredEventStorePollingOptimizer(logName, 100, 50, 2_000, 0.1d);
            case PHASE1_1S -> new NotifyAwareEventStorePollingOptimizer(epochSource, table, new NotifyPollingSettings(true, Duration.ofMillis(50), Duration.ofSeconds(1), 2.0, false));
            case S2_5S, S2_5S_10MS -> new NotifyAwareEventStorePollingOptimizer(epochSource, table, new NotifyPollingSettings(true, Duration.ofMillis(50), Duration.ofSeconds(5), 2.0, true));
        };
    }

    /** Index and sequential scans of the measured event-stream tables, after flushing this backend's statistics */
    private static long scans() {
        LockSupport.parkNanos(Duration.ofMillis(1_100).toNanos()); // other backends flush their statistics at most once a second
        return jdbi.withHandle(h -> h.createQuery("SELECT coalesce(sum(coalesce(idx_scan, 0) + coalesce(seq_scan, 0)), 0) FROM pg_stat_user_tables WHERE relname LIKE 'wakeuporders%'")
                                     .mapTo(Long.class)
                                     .one());
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }

    private static double median(double[] values) {
        var sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void line(String format, Object... args) {
        var text = String.format(format, args);
        log.info(text);
        report.append(text).append('\n');
    }

    private record RunResult(double[] latenciesMs, double pollsPerSecondPerSubscriber, double scansPerSecondPerSubscriber, long burstTransactions, List<Sample> samples, String breakdown) {
    }

    /** Counts the polls made while measuring */
    private record CountingOptimizer(EventStorePollingOptimizer delegate, AtomicLong polls, AtomicBoolean measuring, Queue<Long> pollStarts) implements EventStorePollingOptimizer {
        @Override
        public void eventStorePollingReturnedNoEvents() {
            delegate.eventStorePollingReturnedNoEvents();
        }

        @Override
        public void eventStorePollingReturnedEvents() {
            delegate.eventStorePollingReturnedEvents();
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldSkipPolling() {
            return delegate.shouldSkipPolling();
        }

        @Override
        public long currentDelayMs() {
            return delegate.currentDelayMs();
        }

        @Override
        public boolean mayRepollImmediatelyAfterAnEmptyPoll() {
            return delegate.mayRepollImmediatelyAfterAnEmptyPoll();
        }

        @Override
        public void eventStorePollingStarting() {
            if (measuring.get()) polls.incrementAndGet();
            pollStarts.add(System.nanoTime());
            delegate.eventStorePollingStarting();
        }

        @Override
        public void awaitNextPoll(long delayMs) throws InterruptedException {
            delegate.awaitNextPoll(delayMs);
        }
    }
}
