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

package dk.trustworks.essentials.components.queue.postgresql;

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the batched multi-queue fetch strategy
 * ({@link PostgresqlDurableQueues#fetchNextBatchOfMessagesBatched(Collection, Map, Map)}) that the
 * {@link CentralizedMessageFetcher} switches to once batched fetching has been opted in to via
 * {@link PostgresqlDurableQueuesBuilder#setUseBatchedFetch(boolean)} and the number of active queues exceeds
 * {@link PostgresqlDurableQueuesBuilder#setBatchedFetchSwitchThreshold(int)}.
 * <p>
 * Most tests drive {@code fetchNextBatchOfMessagesBatched} directly. To keep those deterministic the
 * {@link PostgresqlDurableQueues} instance is deliberately <em>not</em> started: the
 * {@link CentralizedMessageFetcher}'s scheduler is only started from {@link PostgresqlDurableQueues#start()},
 * so no background poll can consume messages underneath an assertion. Consumers are still registered via
 * {@code consumeFromQueue}, because the batched fetch skips any queue without a registered consumer.
 * <p>
 * The end-to-end tests at the bottom do start the instance and exercise the strategy through the fetcher,
 * including the queue-count threshold that selects between per-queue and batched fetching.
 */
@Testcontainers
class BatchedFetchStrategyIT {

    /**
     * The batched-fetch switch threshold used throughout this test. Mirrors the production default, which comes
     * from the queue-fetch-strategy benchmark.
     */
    private static final int BATCHED_FETCH_SWITCH_THRESHOLD = 4;

    /**
     * Queue count used whenever a test needs the {@link CentralizedMessageFetcher} to actually choose the
     * batched strategy - it must exceed {@link #BATCHED_FETCH_SWITCH_THRESHOLD}.
     */
    private static final int QUEUE_COUNT_ABOVE_THRESHOLD = BATCHED_FETCH_SWITCH_THRESHOLD + 2;

    /**
     * Long enough that the scheduler's periodic poll never fires during a test. The initial poll at start()
     * still runs immediately, but at that point no consumer is registered yet, so it is a no-op.
     */
    private static final Duration NON_INTERFERING_POLLING_INTERVAL = Duration.ofHours(1);

    private static final QueuedMessageHandler NO_OP_HANDLER = _msg -> {
    };

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("queue-db");

    private JdbiUnitOfWorkFactory         unitOfWorkFactory;
    private List<PostgresqlDurableQueues> createdDurableQueues;
    /**
     * Held so {@link #cleanup()} can close it. The container is shared by every test in this class, so a pool that is
     * left open per test method exhausts PostgreSQL's max_connections part-way through the class.
     */
    private HikariDataSource              dataSource;

    @BeforeEach
    void setup() {
        unitOfWorkFactory = createUnitOfWorkFactory();
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle()
                                                    .execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
        createdDurableQueues = new ArrayList<>();
    }

    @AfterEach
    void cleanup() {
        if (createdDurableQueues != null) {
            createdDurableQueues.forEach(durableQueues -> {
                if (durableQueues.isStarted()) {
                    durableQueues.stop();
                }
            });
        }
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Ordered/unordered selection - the exclude-keys handling that differs between the two strategies
    // ------------------------------------------------------------------------------------------------

    @Test
    void unordered_messages_are_fetched_when_no_keys_are_excluded() {
        var durableQueues = newDurableQueues();
        var queueName     = registerQueues(durableQueues, 1).get(0);

        durableQueues.queueMessage(queueName, Message.of("unordered-1"));
        durableQueues.queueMessage(queueName, Message.of("unordered-2"));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                     Map.of(),
                                                                     Map.of(queueName, 10));

        assertThat(payloadsOf(messages)).containsExactlyInAnyOrder("unordered-1", "unordered-2");
    }

    /**
     * Regression test: unordered messages carry a NULL key, so they can never match an excluded key.
     * A naive {@code NOT (key = ANY(exclude_keys))} predicate evaluates to NULL - not TRUE - for those rows,
     * which silently starves every unordered message on any queue that has an in-flight ordered key.
     */
    @Test
    void unordered_messages_are_still_fetched_when_the_queue_has_excluded_keys() {
        var durableQueues = newDurableQueues();
        var queueName     = registerQueues(durableQueues, 1).get(0);

        durableQueues.queueMessage(queueName, Message.of("unordered-1"));
        durableQueues.queueMessage(queueName, Message.of("unordered-2"));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                     Map.of(queueName, Set.of("some-in-flight-key")),
                                                                     Map.of(queueName, 10));

        assertThat(payloadsOf(messages))
                .as("Unordered messages have a NULL key and must not be filtered out by the exclude-keys predicate")
                .containsExactlyInAnyOrder("unordered-1", "unordered-2");
    }

    @Test
    void excluded_ordered_keys_are_withheld_while_other_messages_are_still_fetched() {
        var durableQueues = newDurableQueues();
        var queueName     = registerQueues(durableQueues, 1).get(0);

        durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-excluded", "key-in-flight", 0));
        durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-eligible", "key-free", 0));
        durableQueues.queueMessage(queueName, Message.of("unordered"));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                     Map.of(queueName, Set.of("key-in-flight")),
                                                                     Map.of(queueName, 10));

        assertThat(payloadsOf(messages)).containsExactlyInAnyOrder("ordered-eligible", "unordered");
    }

    @Test
    void ordered_messages_for_the_same_key_are_released_one_at_a_time() {
        var durableQueues = newDurableQueues();
        var queueName     = registerQueues(durableQueues, 1).get(0);

        durableQueues.queueMessage(queueName, OrderedMessage.of("order-0", "key", 0));
        durableQueues.queueMessage(queueName, OrderedMessage.of("order-1", "key", 1));
        durableQueues.queueMessage(queueName, OrderedMessage.of("order-2", "key", 2));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                     Map.of(),
                                                                     Map.of(queueName, 10));

        assertThat(payloadsOf(messages))
                .as("Only the lowest key_order for a given key may be in flight at any time")
                .containsExactly("order-0");
    }

    // ------------------------------------------------------------------------------------------------
    // Eligibility filters
    // ------------------------------------------------------------------------------------------------

    @Test
    void dead_letter_messages_are_not_fetched() {
        var durableQueues = newDurableQueues();
        var queueName     = registerQueues(durableQueues, 1).get(0);

        var deadLetterId = durableQueues.queueMessage(queueName, Message.of("dead-letter"));
        durableQueues.queueMessage(queueName, Message.of("deliverable"));
        durableQueues.markAsDeadLetterMessage(deadLetterId);

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                     Map.of(),
                                                                     Map.of(queueName, 10));

        assertThat(payloadsOf(messages)).containsExactly("deliverable");
    }

    @Test
    void messages_with_a_future_delivery_timestamp_are_not_fetched() {
        var durableQueues = newDurableQueues();
        var queueName     = registerQueues(durableQueues, 1).get(0);

        durableQueues.queueMessage(queueName, Message.of("delayed"), Duration.ofHours(1));
        durableQueues.queueMessage(queueName, Message.of("deliverable"));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                     Map.of(),
                                                                     Map.of(queueName, 10));

        assertThat(payloadsOf(messages)).containsExactly("deliverable");
    }

    @Test
    void messages_already_being_delivered_are_not_fetched_again() {
        var durableQueues = newDurableQueues();
        var queueName     = registerQueues(durableQueues, 1).get(0);

        durableQueues.queueMessage(queueName, Message.of("unordered-1"));
        durableQueues.queueMessage(queueName, Message.of("unordered-2"));

        var firstFetch = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                       Map.of(),
                                                                       Map.of(queueName, 10));
        assertThat(firstFetch).hasSize(2);

        var secondFetch = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                        Map.of(),
                                                                        Map.of(queueName, 10));

        assertThat(secondFetch)
                .as("Messages marked as being delivered by the first fetch must not be handed out twice")
                .isEmpty();
    }

    @Test
    void fetched_messages_are_marked_as_being_delivered_and_have_their_attempt_count_incremented() {
        var durableQueues = newDurableQueues();
        var queueName     = registerQueues(durableQueues, 1).get(0);

        durableQueues.queueMessage(queueName, Message.of("unordered"));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                     Map.of(),
                                                                     Map.of(queueName, 10));
        assertThat(messages).hasSize(1);

        var persisted = durableQueues.getQueuedMessage(messages.get(0).getId());
        assertThat(persisted).isPresent();
        assertThat(persisted.get().isBeingDelivered()).isTrue();
        assertThat(persisted.get().getTotalDeliveryAttempts()).isEqualTo(1);
        assertThat(persisted.get().getDeliveryTimestamp()).isNotNull();
    }

    // ------------------------------------------------------------------------------------------------
    // Per-queue isolation and skipping
    // ------------------------------------------------------------------------------------------------

    @Test
    void exclude_keys_are_applied_per_queue_and_do_not_leak_across_queues() {
        var durableQueues = newDurableQueues();
        var queueNames    = registerQueues(durableQueues, QUEUE_COUNT_ABOVE_THRESHOLD);
        var excludedQueue = queueNames.get(0);
        var otherQueue    = queueNames.get(1);

        durableQueues.queueMessage(excludedQueue, OrderedMessage.of("excluded-queue-ordered", "shared-key", 0));
        durableQueues.queueMessage(otherQueue, OrderedMessage.of("other-queue-ordered", "shared-key", 0));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(queueNames,
                                                                     Map.of(excludedQueue, Set.of("shared-key")),
                                                                     slotsFor(queueNames, 10));

        assertThat(payloadsOf(messages))
                .as("Excluding a key on one queue must not exclude the same key on another queue")
                .containsExactly("other-queue-ordered");
    }

    @Test
    void queues_without_available_worker_slots_are_skipped() {
        var durableQueues = newDurableQueues();
        var queueNames    = registerQueues(durableQueues, 2);
        var withSlots     = queueNames.get(0);
        var withoutSlots  = queueNames.get(1);

        durableQueues.queueMessage(withSlots, Message.of("has-slots"));
        durableQueues.queueMessage(withoutSlots, Message.of("no-slots"));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(queueNames,
                                                                     Map.of(),
                                                                     Map.of(withSlots, 10, withoutSlots, 0));

        assertThat(payloadsOf(messages)).containsExactly("has-slots");
    }

    @Test
    void queues_without_a_registered_consumer_are_skipped() {
        var durableQueues      = newDurableQueues();
        var registeredQueue    = registerQueues(durableQueues, 1).get(0);
        var unregisteredQueue  = QueueName.of("queue-without-consumer");

        durableQueues.queueMessage(registeredQueue, Message.of("registered"));
        durableQueues.queueMessage(unregisteredQueue, Message.of("unregistered"));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(List.of(registeredQueue, unregisteredQueue),
                                                                     Map.of(),
                                                                     Map.of(registeredQueue, 10, unregisteredQueue, 10));

        assertThat(payloadsOf(messages)).containsExactly("registered");
    }

    @Test
    void no_duplicate_queue_entry_ids_are_returned_across_many_queues() {
        var durableQueues = newDurableQueues();
        var queueNames    = registerQueues(durableQueues, QUEUE_COUNT_ABOVE_THRESHOLD);

        queueNames.forEach(queueName -> {
            durableQueues.queueMessage(queueName, Message.of("unordered-" + queueName));
            durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-" + queueName, "key-" + queueName, 0));
        });

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(queueNames,
                                                                     Map.of(),
                                                                     slotsFor(queueNames, 10));

        var ids = messages.stream().map(QueuedMessage::getId).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(messages).hasSize(queueNames.size() * 2);
    }

    // ------------------------------------------------------------------------------------------------
    // Documented divergence from the per-queue strategy
    //
    // The two strategies are NOT equivalent, and the CentralizedMessageFetcher silently switches between
    // them at the queue-count threshold. These tests pin the differences so any future change is deliberate.
    // ------------------------------------------------------------------------------------------------

    /**
     * Per-queue fetch ({@code useOrderedUnorderedQuery=true}) is ordered-priority: it runs the ordered query
     * first and only falls back to the unordered query when the ordered query returned nothing. Batched fetch
     * numbers ordered and unordered candidates in a single oldest-first window, so it also returns unordered
     * messages while ordered messages are available.
     * <p>
     * Consequence: crossing the queue-count threshold changes which messages get delivered.
     */
    @Test
    void batched_fetch_returns_unordered_messages_alongside_ordered_whereas_per_queue_fetch_prioritises_ordered() {
        var queueNames = List.of(QueueName.of("parity-queue-0"), QueueName.of("parity-queue-1"));
        var excludeKeys = queueNames.stream()
                                    .collect(Collectors.toMap(queueName -> queueName,
                                                              _queueName -> Set.of("key-in-flight")));
        var slots = slotsFor(queueNames, 10);

        var perQueueResult = withFreshQueueStorage(durableQueues -> {
            registerQueues(durableQueues, queueNames);
            seedMixedWorkload(durableQueues, queueNames);
            return payloadsOf(durableQueues.fetchNextBatchOfMessages(queueNames, excludeKeys, slots));
        });

        var batchedResult = withFreshQueueStorage(durableQueues -> {
            registerQueues(durableQueues, queueNames);
            seedMixedWorkload(durableQueues, queueNames);
            return payloadsOf(durableQueues.fetchNextBatchOfMessagesBatched(queueNames, excludeKeys, slots));
        });

        assertThat(perQueueResult)
                .as("Per-queue fetch only returns ordered messages while any are eligible")
                .containsExactlyInAnyOrder("ordered-eligible-parity-queue-0",
                                           "ordered-eligible-parity-queue-1");

        assertThat(batchedResult)
                .as("Batched fetch returns everything eligible, ordered and unordered alike")
                .containsExactlyInAnyOrder("ordered-eligible-parity-queue-0",
                                           "ordered-eligible-parity-queue-1",
                                           "unordered-a-parity-queue-0",
                                           "unordered-b-parity-queue-0",
                                           "unordered-a-parity-queue-1",
                                           "unordered-b-parity-queue-1");

        assertThat(batchedResult)
                .as("Whatever per-queue fetch considers eligible must also be eligible for batched fetch")
                .containsAll(perQueueResult);
    }

    /**
     * The available-worker-slot limit must cap the total number of messages handed to a queue. Numbering the
     * ordered and unordered candidates in separate windows would apply the limit twice, letting a queue be
     * handed up to 2x its slots - and any message that cannot be dispatched to a worker is already flagged
     * {@code is_being_delivered}, so it would sit parked until the stuck-message reset sweeps it.
     */
    @Test
    void batched_fetch_never_returns_more_than_the_available_worker_slots_per_queue() {
        var availableSlots = 1;
        var queueNames     = List.of(QueueName.of("slots-queue"));

        var perQueueResult = withFreshQueueStorage(durableQueues -> {
            registerQueues(durableQueues, queueNames);
            seedOrderedAndUnordered(durableQueues, queueNames.get(0));
            return payloadsOf(durableQueues.fetchNextBatchOfMessages(queueNames, Map.of(), slotsFor(queueNames, availableSlots)));
        });

        var batchedResult = withFreshQueueStorage(durableQueues -> {
            registerQueues(durableQueues, queueNames);
            seedOrderedAndUnordered(durableQueues, queueNames.get(0));
            return payloadsOf(durableQueues.fetchNextBatchOfMessagesBatched(queueNames, Map.of(), slotsFor(queueNames, availableSlots)));
        });

        assertThat(perQueueResult)
                .as("Per-queue fetch never exceeds the available worker slots")
                .hasSize(availableSlots);

        assertThat(batchedResult)
                .as("Batched fetch must cap the ordered and unordered candidates together, not separately")
                .hasSize(availableSlots);
    }

    /**
     * Batched fetch orders ordered and unordered candidates together by {@code next_delivery_ts}, oldest
     * first, so neither kind can starve the other. The per-key barrier still applies to ordered messages.
     */
    @Test
    void batched_fetch_fills_the_available_slots_oldest_first_regardless_of_ordered_or_unordered() {
        var durableQueues = newDurableQueues();
        var queueName     = registerQueues(durableQueues, 1).get(0);

        // Queued oldest -> newest; next_delivery_ts is assigned at queueing time
        durableQueues.queueMessage(queueName, Message.of("unordered-oldest"));
        durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-middle", "key-a", 0));
        durableQueues.queueMessage(queueName, Message.of("unordered-newest"));

        var messages = durableQueues.fetchNextBatchOfMessagesBatched(List.of(queueName),
                                                                     Map.of(),
                                                                     Map.of(queueName, 2));

        assertThat(payloadsOf(messages))
                .as("The two oldest eligible messages win the two slots, whatever their delivery mode")
                .containsExactlyInAnyOrder("unordered-oldest", "ordered-middle");
    }

    // ------------------------------------------------------------------------------------------------
    // End-to-end through the CentralizedMessageFetcher, including the strategy switch threshold
    // ------------------------------------------------------------------------------------------------

    @Test
    void all_messages_are_consumed_when_the_queue_count_exceeds_the_batched_fetch_threshold() {
        assertAllMessagesConsumedEndToEnd(QUEUE_COUNT_ABOVE_THRESHOLD, BATCHED_FETCH_SWITCH_THRESHOLD);
    }

    @Test
    void all_messages_are_consumed_when_the_queue_count_is_at_or_below_the_batched_fetch_threshold() {
        assertAllMessagesConsumedEndToEnd(BATCHED_FETCH_SWITCH_THRESHOLD, BATCHED_FETCH_SWITCH_THRESHOLD);
    }

    @Test
    void all_messages_are_consumed_when_the_threshold_forces_batched_fetching_for_a_single_queue() {
        assertAllMessagesConsumedEndToEnd(1, 0);
    }

    /**
     * The production shape of the unordered-starvation regression: ordered messages held in flight populate
     * the fetcher's exclude-keys map, and the unordered messages on the same queues must still be delivered.
     */
    @Test
    void mixed_ordered_and_unordered_messages_are_all_consumed_above_the_batched_fetch_threshold() {
        var durableQueues = newDurableQueues(BATCHED_FETCH_SWITCH_THRESHOLD, Duration.ofMillis(20));
        var handler       = new RecordingHandler();
        var queueNames    = registerQueues(durableQueues, QUEUE_COUNT_ABOVE_THRESHOLD, handler);

        var expectedPayloads = new ArrayList<String>();
        for (var queueName : queueNames) {
            for (int i = 0; i < 5; i++) {
                var orderedPayload = "ordered-" + queueName + "-" + i;
                durableQueues.queueMessage(queueName, OrderedMessage.of(orderedPayload, "key-" + queueName, i));
                expectedPayloads.add(orderedPayload);

                var unorderedPayload = "unordered-" + queueName + "-" + i;
                durableQueues.queueMessage(queueName, Message.of(unorderedPayload));
                expectedPayloads.add(unorderedPayload);
            }
        }

        durableQueues.start();

        Awaitility.waitAtMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(handler.payloads())
                          .containsExactlyInAnyOrderElementsOf(expectedPayloads));
    }

    @Test
    void competing_consumers_above_the_threshold_deliver_every_message_exactly_once() {
        var messagesPerQueue = 10;

        var handler1       = new RecordingHandler();
        var durableQueues1 = newDurableQueues(BATCHED_FETCH_SWITCH_THRESHOLD, Duration.ofMillis(20));
        var queueNames     = registerQueues(durableQueues1, QUEUE_COUNT_ABOVE_THRESHOLD, handler1);

        var handler2       = new RecordingHandler();
        var durableQueues2 = newDurableQueues(BATCHED_FETCH_SWITCH_THRESHOLD, Duration.ofMillis(20));
        registerQueues(durableQueues2, queueNames, handler2);

        var expectedPayloads = new ArrayList<String>();
        for (var queueName : queueNames) {
            for (int i = 0; i < messagesPerQueue; i++) {
                var payload = "message-" + queueName + "-" + i;
                durableQueues1.queueMessage(queueName, Message.of(payload));
                expectedPayloads.add(payload);
            }
        }

        durableQueues1.start();
        durableQueues2.start();

        Awaitility.waitAtMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> {
                      var delivered = new ArrayList<String>();
                      delivered.addAll(handler1.payloads());
                      delivered.addAll(handler2.payloads());
                      assertThat(delivered).containsExactlyInAnyOrderElementsOf(expectedPayloads);
                  });

        var delivered = new ArrayList<String>();
        delivered.addAll(handler1.payloads());
        delivered.addAll(handler2.payloads());
        assertThat(delivered)
                .as("Competing consumers must not deliver the same message twice")
                .doesNotHaveDuplicates();
        // Note: no assertion that BOTH instances received messages - which instance wins each message is a
        // genuine race, and a fast instance legitimately drains every queue before the other one polls.
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private void assertAllMessagesConsumedEndToEnd(int queueCount, int batchedFetchSwitchThreshold) {
        var messagesPerQueue = 10;
        var durableQueues    = newDurableQueues(batchedFetchSwitchThreshold, Duration.ofMillis(20));
        var handler          = new RecordingHandler();
        var queueNames       = registerQueues(durableQueues, queueCount, handler);

        var expectedPayloads = new ArrayList<String>();
        for (var queueName : queueNames) {
            for (int i = 0; i < messagesPerQueue; i++) {
                var payload = "message-" + queueName + "-" + i;
                durableQueues.queueMessage(queueName, Message.of(payload));
                expectedPayloads.add(payload);
            }
        }

        durableQueues.start();

        Awaitility.waitAtMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(handler.payloads())
                          .containsExactlyInAnyOrderElementsOf(expectedPayloads));
    }

    private void seedOrderedAndUnordered(PostgresqlDurableQueues durableQueues, QueueName queueName) {
        durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-a", "key-a", 0));
        durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-b", "key-b", 0));
        durableQueues.queueMessage(queueName, Message.of("unordered-a"));
        durableQueues.queueMessage(queueName, Message.of("unordered-b"));
    }

    private void seedMixedWorkload(PostgresqlDurableQueues durableQueues, List<QueueName> queueNames) {
        for (var queueName : queueNames) {
            durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-in-flight-" + queueName, "key-in-flight", 0));
            durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-eligible-" + queueName, "key-eligible", 0));
            durableQueues.queueMessage(queueName, Message.of("unordered-a-" + queueName));
            durableQueues.queueMessage(queueName, Message.of("unordered-b-" + queueName));
        }
    }

    /**
     * Runs {@code action} against a freshly created queue table so that two fetch strategies can be compared
     * over identical starting state.
     */
    private <T> T withFreshQueueStorage(java.util.function.Function<PostgresqlDurableQueues, T> action) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle()
                                                    .execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
        return action.apply(newDurableQueues());
    }

    private PostgresqlDurableQueues newDurableQueues() {
        return newDurableQueues(BATCHED_FETCH_SWITCH_THRESHOLD);
    }

    private PostgresqlDurableQueues newDurableQueues(int batchedFetchSwitchThreshold) {
        return newDurableQueues(batchedFetchSwitchThreshold, NON_INTERFERING_POLLING_INTERVAL);
    }

    private PostgresqlDurableQueues newDurableQueues(int batchedFetchSwitchThreshold, Duration pollingInterval) {
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setUseCentralizedMessageFetcher(true)
                                                   .setUseOrderedUnorderedQuery(true)
                                                   // Batched fetching is opt-in in production; every test here is about that path
                                                   .setUseBatchedFetch(true)
                                                   .setBatchedFetchSwitchThreshold(batchedFetchSwitchThreshold)
                                                   .setCentralizedMessageFetcherPollingInterval(pollingInterval)
                                                   // Keep the stuck-message reset well out of the way so it can never be
                                                   // confused with a genuine duplicate delivery
                                                   .setMessageHandlingTimeout(Duration.ofMinutes(10))
                                                   .build();
        createdDurableQueues.add(durableQueues);
        return durableQueues;
    }

    private List<QueueName> registerQueues(PostgresqlDurableQueues durableQueues, int queueCount) {
        return registerQueues(durableQueues, queueCount, NO_OP_HANDLER);
    }

    private List<QueueName> registerQueues(PostgresqlDurableQueues durableQueues,
                                           int queueCount,
                                           QueuedMessageHandler handler) {
        var queueNames = IntStream.range(0, queueCount)
                                  .mapToObj(i -> QueueName.of("batched-fetch-queue-" + i))
                                  .toList();
        registerQueues(durableQueues, queueNames, handler);
        return queueNames;
    }

    private void registerQueues(PostgresqlDurableQueues durableQueues, List<QueueName> queueNames) {
        registerQueues(durableQueues, queueNames, NO_OP_HANDLER);
    }

    private void registerQueues(PostgresqlDurableQueues durableQueues,
                                List<QueueName> queueNames,
                                QueuedMessageHandler handler) {
        for (var queueName : queueNames) {
            durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                           .setQueueName(queueName)
                                                           .setConsumerName("consumer-" + queueName)
                                                           .setParallelConsumers(2)
                                                           .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                           .setQueueMessageHandler(handler)
                                                           .build());
        }
    }

    private Map<QueueName, Integer> slotsFor(List<QueueName> queueNames, int slots) {
        return queueNames.stream().collect(Collectors.toMap(queueName -> queueName, _queueName -> slots));
    }

    private List<String> payloadsOf(List<QueuedMessage> messages) {
        return messages.stream().map(message -> (String) message.getPayload()).toList();
    }

    private JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        dataSource.setUsername(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());
        dataSource.setAutoCommit(false);
        dataSource.setMaximumPoolSize(20);

        return new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
    }

    private static class RecordingHandler implements QueuedMessageHandler {
        private final ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();

        @Override
        public void handle(QueuedMessage message) {
            received.add((String) message.getPayload());
        }

        List<String> payloads() {
            return new ArrayList<>(received);
        }
    }
}
