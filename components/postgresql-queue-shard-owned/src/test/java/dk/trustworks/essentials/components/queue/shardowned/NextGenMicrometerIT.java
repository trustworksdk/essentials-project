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
import org.testcontainers.containers.PostgreSQLContainer;
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
class NextGenMicrometerIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

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
        NextGenSchema.create(dataSource, SHARD_COUNT);
        NextGenSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
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
        try (var queue = new NextGenMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "micro-1")) {
            queue.addObserver(new MicrometerQueueObserver(registry, List.of(Tag.of("queue", "orders"))));

            // Every meter exists before anything has happened. A counter that materialises only when
            // something goes wrong cannot be alerted on: the series is absent until the incident.
            assertThat(counter(MicrometerQueueObserver.DEAD_LETTER_COUNTER, "queue", "orders")).isZero();
            assertThat(counter(MicrometerQueueObserver.RETRIES_COUNTER, "queue", "orders")).isZero();

            var subscription = queue.consume((key, payload) -> {
                var body = new String(payload, StandardCharsets.UTF_8);
                if (body.startsWith("poison")) {
                    // Fails every attempt, so it is retried and then dead-lettered.
                    attempts.incrementAndGet();
                    throw new IllegalStateException("poison message");
                }
            }, new ConsumerOptions(8, Integer.MAX_VALUE, 2, Duration.ofMillis(20), 1.0d, Duration.ofSeconds(1)));

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
            assertThat(timer.count()).as("successful deliveries must be timed").isGreaterThanOrEqualTo(21L);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();

            subscription.close();
        }
    }

    /**
     * Depth is a query, not an event, so it is opt-in and cached. Both halves matter: the gauge has to
     * report the real depth, and it must not issue a query per scrape.
     */
    @Test
    void depth_gauges_report_the_queue_and_are_not_re_queried_on_every_scrape() throws Exception {
        try (var queue = new NextGenMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "micro-2")) {
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
        public Subscription consume(MessageHandler handler, ConsumerOptions options) throws java.sql.SQLException {
            return delegate.consume(handler, options);
        }

        @Override
        public QueueSession openSession(SessionScope scope, Duration leaseDuration) throws java.sql.SQLException {
            return delegate.openSession(scope, leaseDuration);
        }

        @Override
        public List<DeadLetter> deadLetters(int offset, int limit) throws java.sql.SQLException {
            return delegate.deadLetters(offset, limit);
        }

        @Override
        public boolean resurrect(MessageId messageId) throws java.sql.SQLException {
            return delegate.resurrect(messageId);
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
}
