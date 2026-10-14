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

package dk.trustworks.essentials.components.queue.shardowned;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.observability.micrometer.MicrometerQueueObserver;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Micrometer binding, measured against real traffic rather than against the observer interface.
 * <p>
 * The risk this test exists for is not that Micrometer is wired up wrong — it is that the engine
 * never <em>calls</em> the callback a meter is attached to. Three of the seven were dead when the
 * binding was written: {@code retryScheduled}, {@code deadLettered} and {@code shardOwnershipChanged}
 * had no call site anywhere in the engine, so a binding over them would have published counters that
 * stayed at zero through every retry and every dead letter — the two things a queue gets paged for.
 * A test that mocks the observer cannot catch that. This one drives the engine and reads the meters.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedMicrometerIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource    dataSource;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(30);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private double counter(String name, String... tagPairs) {
        var tags = new ArrayList<Tag>();
        for (var index = 0; index < tagPairs.length; index += 2) {
            tags.add(Tag.of(tagPairs[index], tagPairs[index + 1]));
        }
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? -1.0d : counter.count();
    }

    @Test
    void every_meter_the_binding_declares_is_fed_by_the_engine() throws Exception {
        var attempts = new AtomicInteger();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "micro-1")) {
            queue.addObserver(new MicrometerQueueObserver(registry, List.of(Tag.of("queue", "orders"))));

            // Every meter exists before anything has happened. A counter that materialises only when
            // something goes wrong cannot be alerted on: the series is absent until the incident.
            assertThat(counter(MicrometerQueueObserver.DEAD_LETTER_COUNTER, "queue", "orders")).isZero();
            assertThat(counter(MicrometerQueueObserver.RETRIES_COUNTER, "queue", "orders")).isZero();

            var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                var body = new String(payload, StandardCharsets.UTF_8);
                if (body.startsWith("poison")) {
                    // Fails every attempt, so it is retried and then dead-lettered.
                    attempts.incrementAndGet();
                    throw new IllegalStateException("poison message");
                }
            }, new ConsumerOptions(8, Integer.MAX_VALUE, 2, RetryBackoff.exponential(Duration.ofMillis(20), 1.0d, Duration.ofSeconds(1))));

            var messages = new ArrayList<Message>();
            for (var index = 0; index < 20; index++) {
                messages.add(Message.of(("ok-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            messages.add(Message.of("poison".getBytes(StandardCharsets.UTF_8), 1));
            messages.add(Message.ordered("ordered-ok".getBytes(StandardCharsets.UTF_8), 1, "k-1", 0L));
            messages.add(Message.ordered("poison-ordered".getBytes(StandardCharsets.UTF_8), 1, "k-2", 0L));
            queue.enqueue(messages);

            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(counter(MicrometerQueueObserver.DEAD_LETTER_COUNTER, "queue", "orders"))
                        .as("a message that exhausts its attempts must reach the dead-letter counter")
                        .isGreaterThanOrEqualTo(2.0d);
                assertThat(counter(MicrometerQueueObserver.RETRIES_COUNTER, "queue", "orders"))
                        .as("a scheduled redelivery must reach the retry counter")
                        .isPositive();
                // Inside the await, not after it. The poison message's shard can finish retrying
                // and dead-lettering while other shards are still delivering, so the dead-letter
                // counter says nothing about whether the successful deliveries have all landed.
                var deliveryTimer = registry.find(MicrometerQueueObserver.DELIVERY_TIMER).timer();
                assertThat(deliveryTimer).as("the delivery timer must exist").isNotNull();
                assertThat(deliveryTimer.count()).as("successful deliveries must be timed").isGreaterThanOrEqualTo(21L);
            });

            assertThat(counter(MicrometerQueueObserver.ENQUEUED_COUNTER, "queue", "orders", "lane", "unordered"))
                    .isEqualTo(21.0d);
            assertThat(counter(MicrometerQueueObserver.ENQUEUED_COUNTER, "queue", "orders", "lane", "ordered"))
                    .isEqualTo(2.0d);
            assertThat(counter(MicrometerQueueObserver.FAILURES_COUNTER, "queue", "orders")).isPositive();
            assertThat(counter(MicrometerQueueObserver.OWNERSHIP_COUNTER, "queue", "orders", "change", "acquired"))
                    .as("shards taken at start-up are ownership changes and must be counted")
                    .isPositive();

            var timer = registry.find(MicrometerQueueObserver.DELIVERY_TIMER).timer();
            assertThat(timer).isNotNull();
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();

            subscription.close();
        }
    }

    /**
     * A shard whose takeover keeps failing is leased and unserved, so {@code shards.unowned} cannot see it, and the
     * engine's own count of it ({@code ShardOwnerMetrics.shardsAwaitingTakeover}) is private behind a
     * {@code MessageQueue}. Before {@code QueueObserver.takeoverStalled}, a starter or adapter user had only the ERROR log.
     * Driven by a real failing takeover - the takeover's dead-letter move refused by a trigger - through the
     * {@code MessageQueue} the starter wires, not by calling the observer. A throwing observer registered ahead of the
     * Micrometer one pins the fan-out's guarantee: every observer hears both ends of the stall, whatever an earlier one throws.
     */
    @Test
    void a_takeover_that_keeps_failing_shows_on_the_awaiting_takeover_gauge_until_it_completes() throws Exception {
        var maxAttempts = 3;
        // Shard 0 holds a row one attempt short of the limit, and its lease names an instance that is gone. Taking it
        // over is therefore unclean: the takeover charges the row to the limit and must dead-letter it, which the
        // trigger refuses, so the takeover fails and is retried every 200 ms
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + ShardOwnedSchema.UNORDERED_TABLE + " (queue_id, shard, seq, payload, payload_type, attempts)"
                             + " VALUES (?, 0, 1, '\\x00'::bytea, 1, ?)")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, maxAttempts - 1);
            statement.executeUpdate();
        }
        assertThat(new ShardOwnedStorage(dataSource, QUEUE_ID).acquireLease("unordered", 0, "crashed-instance", 60_000)).isPresent();
        failDeadLetterInserts(true);

        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "stall-1")) {
            // Registered first, and throwing on every call: the fan-out must still tell the Micrometer observer behind
            // it both ends of the stall, or the gauge never rises - or, if only the end were lost, never falls again
            var brokenObserverCalls = new AtomicInteger();
            queue.addObserver(new QueueObserver() {
                @Override
                public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
                    brokenObserverCalls.incrementAndGet();
                    throw new IllegalStateException("broken observer");
                }
            });
            queue.addObserver(new MicrometerQueueObserver(registry, List.of(Tag.of(MicrometerQueueObserver.QUEUE_TAG, "orders"))));
            var gauge = registry.find(MicrometerQueueObserver.SHARDS_AWAITING_TAKEOVER_GAUGE).tag(MicrometerQueueObserver.QUEUE_TAG, "orders").gauge();
            assertThat(gauge).as("registered eagerly, so an alert can exist before the incident does").isNotNull();
            assertThat(gauge.value()).isZero();

            var subscription = queue.consume((messageId, key, payload, payloadType) -> {
            }, new ConsumerOptions(8, Integer.MAX_VALUE, maxAttempts, RetryBackoff.exponential(Duration.ofMillis(20), 1.0d, Duration.ofSeconds(1))));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(gauge.value()).as("the stalled shard is published").isEqualTo(1.0d));
            // Still one a second later: told once per stall, not once per retry (five a second)
            Thread.sleep(1_000);
            assertThat(gauge.value()).isEqualTo(1.0d);
            assertThat(counter(MicrometerQueueObserver.DEAD_LETTER_COUNTER, "queue", "orders"))
                    .as("the injected failure must be what stalls the takeover").isZero();

            failDeadLetterInserts(false);
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> {
                          assertThat(gauge.value()).as("and drops to zero once the takeover completes").isZero();
                          assertThat(counter(MicrometerQueueObserver.DEAD_LETTER_COUNTER, "queue", "orders"))
                                  .as("the completed takeover parked the exhausted row").isEqualTo(1.0d);
                      });
            assertThat(brokenObserverCalls.get()).as("the throwing observer was told the start and the end as well").isEqualTo(2);
            subscription.close();
        }
    }

    /**
     * A trigger rather than a broken statement: the takeover's own dead-letter move has to be the thing that fails. A
     * revoked privilege would not do: the test role owns the table.
     */
    private void failDeadLetterInserts(boolean fail) throws java.sql.SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("""
                                  CREATE OR REPLACE FUNCTION fail_dead_letter_insert() RETURNS trigger AS $$
                                  BEGIN
                                      RAISE EXCEPTION 'injected dead-letter failure';
                                  END
                                  $$ LANGUAGE plpgsql""");
                statement.execute("CREATE TRIGGER fail_dead_letter_insert BEFORE INSERT ON " + ShardOwnedSchema.DLQ_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_dead_letter_insert()");
            } else {
                statement.execute("DROP TRIGGER fail_dead_letter_insert ON " + ShardOwnedSchema.DLQ_TABLE);
            }
        }
    }

    /**
     * Depth is a query, not an event, so it is opt-in and cached. Both halves matter: the gauge has to
     * report the real depth, and it must not issue a query per scrape.
     */
    @Test
    void depth_gauges_report_the_queue_and_are_not_re_queried_on_every_scrape() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "micro-2")) {
            var counting = new CountingQueue(queue);
            new MicrometerQueueObserver(registry).bindQueueDepth(counting, Duration.ofSeconds(30));

            var messages = new ArrayList<Message>();
            for (var index = 0; index < 7; index++) {
                messages.add(Message.of(("m-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            queue.enqueue(messages);

            var gauge = registry.find(MicrometerQueueObserver.DEPTH_GAUGE)
                                .tags(List.of(Tag.of("lane", "unordered"))).gauge();
            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isEqualTo(7.0d);

            var queriesAfterFirstScrape = counting.calls.get();
            for (var scrape = 0; scrape < 25; scrape++) {
                registry.find(MicrometerQueueObserver.DEPTH_GAUGE).gauges().forEach(g -> g.value());
            }
            assertThat(counting.calls.get())
                    .as("scrapes inside the cache window must share one query, not issue one each")
                    .isEqualTo(queriesAfterFirstScrape);
        }
    }

    /** Counts how often the depth query is actually issued. */
    private static final class CountingQueue implements MessageQueue {
        private final MessageQueue  delegate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingQueue(MessageQueue delegate) {
            this.delegate = delegate;
        }

        @Override
        public QueueDepth depth() throws java.sql.SQLException {
            calls.incrementAndGet();
            return delegate.depth();
        }

        @Override
        public List<MessageId> enqueue(List<Message> messages) throws java.sql.SQLException {
            return delegate.enqueue(messages);
        }

        @Override
        public List<MessageId> enqueue(java.sql.Connection connection, List<Message> messages) throws java.sql.SQLException {
            return delegate.enqueue(connection, messages);
        }

        @Override
        public Subscription consume(MessageHandler handler, ConsumerOptions options) throws java.sql.SQLException {
            return delegate.consume(handler, options);
        }

        @Override
        public QueueSession openSession(SessionScope scope, Duration leaseDuration) throws java.sql.SQLException {
            return delegate.openSession(scope, leaseDuration);
        }

        @Override
        public QueueHealth health() throws java.sql.SQLException {
            return delegate.health();
        }

        @Override
        public java.util.Optional<QueuedMessage> getMessage(MessageId messageId) throws java.sql.SQLException {
            return delegate.getMessage(messageId);
        }

        @Override
        public boolean deleteMessage(MessageId messageId) throws java.sql.SQLException {
            return delegate.deleteMessage(messageId);
        }

        @Override
        public boolean retryMessage(MessageId messageId, Duration delay) throws java.sql.SQLException {
            return delegate.retryMessage(messageId, delay);
        }

        @Override
        public boolean markAsDeadLetter(MessageId messageId, String reason) throws java.sql.SQLException {
            return delegate.markAsDeadLetter(messageId, reason);
        }

        @Override
        public List<DeadLetter> deadLetters(int offset, int limit) throws java.sql.SQLException {
            return delegate.deadLetters(offset, limit);
        }

        @Override
        public List<dk.trustworks.essentials.components.queue.shardowned.spi.QueuedMessage> messages(
                int offset, int limit, boolean ascending) throws java.sql.SQLException {
            return delegate.messages(offset, limit, ascending);
        }

        @Override
        public dk.trustworks.essentials.components.queue.shardowned.spi.QueueStatistics statistics() {
            return delegate.statistics();
        }

        @Override
        public boolean hasOrderedMessagesForKey(String key) throws java.sql.SQLException {
            return delegate.hasOrderedMessagesForKey(key);
        }

        @Override
        public boolean resurrect(MessageId messageId) throws java.sql.SQLException {
            return delegate.resurrect(messageId);
        }

        @Override
        public int resurrectKey(String key) throws java.sql.SQLException {
            return delegate.resurrectKey(key);
        }

        @Override
        public long purge() throws java.sql.SQLException {
            return delegate.purge();
        }

        @Override
        public MessageQueue addObserver(QueueObserver observer) {
            return delegate.addObserver(observer);
        }

        @Override
        public MessageQueue addInterceptor(MessageQueueInterceptor interceptor) {
            return delegate.addInterceptor(interceptor);
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public boolean isStarted() {
            return delegate.isStarted();
        }
    }

    /**
     * The signal that was missing. Depth cannot tell a queue nobody is consuming from a queue that is
     * merely busy — every ownership failure this engine has had was invisible in the metrics that
     * existed at the time, and showed up only as a backlog with no attributable cause.
     */
    @Test
    void unowned_shards_are_visible_while_nobody_is_consuming() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "health-1")) {
            var health = queue.health();
            assertThat(health.shardCount()).isEqualTo(SHARD_COUNT);
            assertThat(health.orderedUnits()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
            assertThat(health.unownedShards())
                    .describedAs("nobody is consuming, so every unit of both lanes is unowned — and "
                                 + "the two lanes have different totals now")
                    .isEqualTo(SHARD_COUNT + ShardOwnedSchema.ORDERED_UNITS);
            assertThat(health.fullyOwned()).isFalse();
            assertThat(health.liveInstances()).isZero();

            queue.consume((messageId, key, payload, payloadType) -> {
            }, ConsumerOptions.defaults());

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.health().fullyOwned())
                              .describedAs("with a consumer running, every shard has an owner")
                              .isTrue());
            assertThat(queue.health().unorderedOwned()).isEqualTo(SHARD_COUNT);
            assertThat(queue.health().orderedOwned()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
        }
    }

    @Test
    void the_health_gauges_reach_the_registry() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "health-2")) {
            new MicrometerQueueObserver(registry, List.of(Tag.of(MicrometerQueueObserver.QUEUE_TAG, "orders")))
                    .bindQueueHealth(queue, Duration.ofMillis(1));

            assertThat(registry.find(MicrometerQueueObserver.SHARDS_UNOWNED_GAUGE).gauge())
                    .describedAs("registered eagerly, so an alert can exist before the incident does")
                    .isNotNull();
            assertThat(registry.find(MicrometerQueueObserver.SHARDS_UNOWNED_GAUGE).gauge().value())
                    .describedAs("nothing is consuming yet")
                    .isEqualTo(SHARD_COUNT + ShardOwnedSchema.ORDERED_UNITS * 1.0d);

            queue.consume((messageId, key, payload, payloadType) -> {
            }, ConsumerOptions.defaults());
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(registry.find(MicrometerQueueObserver.SHARDS_UNOWNED_GAUGE)
                                                              .gauge().value())
                              .describedAs("and drops to zero once the shards are owned")
                              .isZero());
            // Leases are taken synchronously by start(); the membership row is written by the first
            // heartbeat, which is a third of the lease lifetime away. So this has to be awaited, not
            // asserted alongside the shard gauges.
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(registry.find(MicrometerQueueObserver.INSTANCES_GAUGE)
                                                              .gauge().value())
                              .describedAs("the instance registers on its first heartbeat")
                              .isEqualTo(1.0d));
        }
    }

    /**
     * Micrometer holds a weak reference to a gauge's state, so a cache created as a local in a bind
     * method is collected once the method returns and every gauge over it reports NaN from then on.
     * Silently, at an arbitrary later moment.
     * <p>
     * A test that asserts straight after binding cannot see this — no collection has happened yet —
     * which is why both the depth and health gauges looked correct while being broken. This one
     * forces the collection first.
     */
    @Test
    void gauges_keep_reporting_after_their_state_could_have_been_collected() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "gc-1")) {
            new MicrometerQueueObserver(registry, List.of(Tag.of(MicrometerQueueObserver.QUEUE_TAG, "orders")))
                    .bindQueueDepth(queue, Duration.ofMillis(1))
                    .bindQueueHealth(queue, Duration.ofMillis(1));
            queue.enqueue(List.of(Message.of("one".getBytes(StandardCharsets.UTF_8), 1)));

            for (var attempt = 0; attempt < 5; attempt++) {
                System.gc();
                Thread.sleep(50);
            }

            assertThat(registry.find(MicrometerQueueObserver.DEPTH_GAUGE)
                               .tag(MicrometerQueueObserver.LANE_TAG, "unordered").gauge().value())
                    .describedAs("the depth gauge must still read the queue, not NaN")
                    .isEqualTo(1.0d);
            assertThat(registry.find(MicrometerQueueObserver.SHARDS_UNOWNED_GAUGE).gauge().value())
                    .describedAs("and so must the ownership gauge")
                    .isEqualTo(SHARD_COUNT + ShardOwnedSchema.ORDERED_UNITS * 1.0d);
        }
    }
}
