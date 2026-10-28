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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ordered-claim statements - the default and the head scan - claim the same messages, and each queue is
 * switched to the one that is cheap for its backlog.
 */
@Testcontainers
class PostgresqlDurableQueuesOrderedClaimStrategyIT {
    private static final String    QUEUE_TABLE = "ordered_claim_queue";
    private static final QueueName QUEUE       = QueueName.of("OrderedClaimQueue");
    private static final QueueName OTHER_QUEUE = QueueName.of("OrderedClaimOtherQueue");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("ordered-claim-db");

    private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private PostgresqlDurableQueues                                       durableQueues;

    @BeforeEach
    void setUp() {
        useUnitOfWorkFactory(new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                   postgreSQLContainer.getUsername(),
                                                                   postgreSQLContainer.getPassword())));
    }

    /**
     * Build the queues on {@code unitOfWorkFactory}, stopping the ones built before
     */
    private void useUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        if (durableQueues != null) {
            durableQueues.stop();
        }
        this.unitOfWorkFactory = unitOfWorkFactory;
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .build();
        durableQueues.purgeQueue(QUEUE);
        consumeFrom(QUEUE);
    }

    /**
     * Registered but never started: the tests drive the claims themselves, and fetching requires a consumer
     */
    private void consumeFrom(QueueName queueName) {
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(queueName)
                                                       .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                       .setParallelConsumers(1)
                                                       .setQueueMessageHandler(message -> { })
                                                       .build());
    }

    @AfterEach
    void tearDown() {
        durableQueues.stop();
    }

    @Test
    void both_statements_claim_the_same_messages() {
        // Keys of uneven length, so heads sit at different key_orders
        queue(i -> OrderedMessage.of("payload-" + i, "key-" + (i % 37), i), 1_000);
        // Heads that must block their key: a dead letter, a message being delivered, and one not yet due
        markHead("key-1", "is_dead_letter_message = TRUE");
        markHead("key-2", "is_being_delivered = TRUE, delivery_ts = now()");
        markHead("key-3", "next_delivery_ts = now() + interval '1 hour'");
        var excluded = Set.of("key-4", "key-5");

        var byDefault  = claimedIds(false, excluded);
        var byHeadScan = claimedIds(true, excluded);

        assertThat(byDefault).hasSize(37 - 5);
        assertThat(byHeadScan).isEqualTo(byDefault);
    }

    @Test
    void a_queue_switches_to_the_head_scan_for_few_long_keys_and_back_for_many_keys() {
        // 20 keys of 1 000: fewer heads than the claim's limit, so the default statement probes every candidate
        queue(i -> OrderedMessage.of("payload-" + i, "key-" + (i % 20), i / 20), 20_000);
        claimUntilHeadScanIs(true, OrderedClaimStatementChooser.SLOW_POLLS_BEFORE_SWITCH);
        assertThat(durableQueues.orderedClaimByHeadScan(QUEUE)).as("switched to the head scan").isTrue();
        claimedIds(null, Set.of());
        assertThat(durableQueues.orderedClaimByHeadScan(QUEUE)).as("the head scan is fast for this backlog, so it stays").isTrue();

        // 20 000 keys of one message: the head scan visits every key. Its first poll is slower than the default
        // statement's last, which switches back at once, or, if not, it probes the default after a few slow polls
        durableQueues.purgeQueue(QUEUE);
        queue(i -> OrderedMessage.of("payload-" + i, "single-" + i, 0), 20_000);
        claimUntilHeadScanIs(false, OrderedClaimStatementChooser.SLOW_POLLS_BEFORE_SWITCH + OrderedClaimStatementChooser.INITIAL_PROBE_INTERVAL);
        assertThat(durableQueues.orderedClaimByHeadScan(QUEUE)).as("switched back to the default statement").isFalse();
        claimedIds(null, Set.of());
        assertThat(durableQueues.orderedClaimByHeadScan(QUEUE)).as("the default is fast for this backlog, so it stays").isFalse();
    }

    @Test
    void a_probe_that_runs_past_its_timeout_is_cancelled_and_the_poll_carries_on() {
        startProbingTheHeadScan(Duration.ofMillis(100));

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            var handle         = uow.handle();
            var startedNanos   = System.nanoTime();
            // Stands in for a head scan planned on stale statistics, which took 41 s at 20 000 keys
            var probe = durableQueues.claimOrdered(handle, QUEUE, true, 64,
                                                   () -> handle.createQuery("SELECT pg_sleep(5)").mapTo(String.class).one(),
                                                   claimed -> 0);

            assertThat(probe).as("cancelled").isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - startedNanos)).isLessThan(Duration.ofSeconds(2));
            // Rolled back to the savepoint: the transaction is not aborted, and the timeout is gone with it
            assertThat(statementTimeout(handle)).isEqualTo("0");
        });
        assertThat(durableQueues.orderedClaimByHeadScan(QUEUE)).as("the cancelled probe lost, so the queue switched back").isFalse();
    }

    @Test
    void a_probe_that_finishes_in_time_keeps_its_claim_and_restores_the_timeout() {
        startProbingTheHeadScan(Duration.ofMillis(500));

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            var handle = uow.handle();
            var probe = durableQueues.claimOrdered(handle, QUEUE, true, 64,
                                                   () -> statementTimeout(handle),
                                                   claimed -> 64);

            assertThat(probe).as("ran under the probe's timeout").contains("500ms");
            assertThat(statementTimeout(handle)).isEqualTo("0");
        });
        assertThat(durableQueues.orderedClaimByHeadScan(QUEUE)).as("the probe claimed in time, so the head scan stays").isTrue();
    }

    /**
     * The probe in a real centralized poll: the head scan is cancelled under a savepoint inside the poll's
     * transaction, and the rest of that transaction - this queue's unordered claim and the other queue's claim -
     * commits. Run on a plain Jdbi unit of work and on the Spring transaction-aware one that
     * {@code spring-boot-starter-postgresql} wires.
     */
    @ParameterizedTest(name = "spring transaction-aware: {0}")
    @ValueSource(booleans = {false, true})
    void a_cancelled_probe_in_a_batched_poll_leaves_the_other_claims_of_the_poll_committed(boolean springTransactionAware) {
        if (springTransactionAware) {
            useUnitOfWorkFactory(springTransactionAwareUnitOfWorkFactory());
        }
        durableQueues.purgeQueue(OTHER_QUEUE);
        consumeFrom(OTHER_QUEUE);
        // 5 000 keys of one message: the head scan visits every key, which takes tens of milliseconds - far past the probe's 1 ms timeout
        queue(QUEUE, i -> OrderedMessage.of("ordered-" + i, "key-" + i, 0), 5_000);
        queue(QUEUE, i -> Message.of("unordered-" + i), 5);
        queue(OTHER_QUEUE, i -> Message.of("other-" + i), 5);

        // Three slow polls of the default statement at 1 µs each switch the queue to probing the head scan, under a
        // timeout of that 1 µs - which rounds up to statement_timeout's smallest, 1 ms
        durableQueues.setOrderedClaimSlowThreshold(Duration.ZERO);
        for (var i = 0; i < OrderedClaimStatementChooser.SLOW_POLLS_BEFORE_SWITCH; i++) {
            durableQueues.recordOrderedClaim(QUEUE, false, 1_000, 0, 64);
        }
        assertThat(durableQueues.orderedClaimByHeadScan(QUEUE)).as("probing the head scan").isTrue();

        var claimed = durableQueues.fetchNextBatchOfMessages(List.of(QUEUE, OTHER_QUEUE), Map.of(), Map.of(QUEUE, 64, OTHER_QUEUE, 64));

        assertThat(claimed).extracting(QueuedMessage::getPayload)
                           .containsExactlyInAnyOrder("unordered-0", "unordered-1", "unordered-2", "unordered-3", "unordered-4",
                                                      "other-0", "other-1", "other-2", "other-3", "other-4");
        assertThat(durableQueues.orderedClaimByHeadScan(QUEUE)).as("the cancelled probe lost, so the queue switched back").isFalse();
        // Read after the poll's unit of work has ended: the claims committed, and the cancelled ordered claim marked nothing
        assertThat(idsBeingDelivered()).isEqualTo(claimed.stream().map(message -> message.getId().toString()).collect(Collectors.toSet()));
    }

    /**
     * Three slow polls of the default statement, with the head scan never measured, switch the queue to probing the head
     * scan, under a timeout of the last poll's elapsed time
     */
    private void startProbingTheHeadScan(Duration slowPoll) {
        durableQueues.setOrderedClaimSlowThreshold(Duration.ofMillis(10));
        for (var i = 0; i < OrderedClaimStatementChooser.SLOW_POLLS_BEFORE_SWITCH; i++) {
            durableQueues.recordOrderedClaim(QUEUE, false, slowPoll.toNanos(), 0, 64);
        }
        assertThat(durableQueues.orderedClaimByHeadScan(QUEUE)).as("probing the head scan").isTrue();
    }

    private static String statementTimeout(org.jdbi.v3.core.Handle handle) {
        return handle.createQuery("SHOW statement_timeout").mapTo(String.class).one();
    }

    private void claimUntilHeadScanIs(boolean headScan, int maxClaims) {
        for (var claims = 0; claims < maxClaims && durableQueues.orderedClaimByHeadScan(QUEUE) != headScan; claims++) {
            claimedIds(null, Set.of());
        }
    }

    /**
     * Claims in a unit of work that is rolled back, so every claim sees the same messages
     *
     * @param headScan {@code null} to let the queue pick
     */
    private Set<QueueEntryId> claimedIds(Boolean headScan, Set<String> excludedKeys) {
        durableQueues.setOrderedClaimByHeadScanOverride(headScan);
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        try {
            var claimed = new HashSet<QueueEntryId>();
            durableQueues.fetchNextBatchOfMessages(List.of(QUEUE), Map.of(QUEUE, excludedKeys), Map.of(QUEUE, 64))
                         .forEach(message -> claimed.add(message.getId()));
            return claimed;
        } finally {
            unitOfWork.rollback();
            durableQueues.setOrderedClaimByHeadScanOverride(null);
        }
    }

    private void queue(IntFunction<Message> messageFactory, int messages) {
        queue(QUEUE, messageFactory, messages);
    }

    private void queue(QueueName queueName, IntFunction<Message> messageFactory, int messages) {
        for (var offset = 0; offset < messages; offset += 1_000) {
            var batch = new ArrayList<Message>(1_000);
            for (var i = offset; i < Math.min(messages, offset + 1_000); i++) {
                batch.add(messageFactory.apply(i));
            }
            unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(queueName, batch));
        }
    }

    private Set<String> idsBeingDelivered() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT id FROM " + QUEUE_TABLE + " WHERE is_being_delivered = TRUE AND queue_name IN (?, ?)")
                                                          .bind(0, QUEUE.toString())
                                                          .bind(1, OTHER_QUEUE.toString())
                                                          .mapTo(String.class)
                                                          .set());
    }

    /**
     * Wired as {@code spring-boot-starter-postgresql} wires it
     */
    private static SpringTransactionAwareJdbiUnitOfWorkFactory springTransactionAwareUnitOfWorkFactory() {
        var dataSource = new DriverManagerDataSource(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        return new SpringTransactionAwareJdbiUnitOfWorkFactory(Jdbi.create(new TransactionAwareDataSourceProxy(dataSource)),
                                                               new DataSourceTransactionManager(dataSource));
    }

    private void markHead(String key, String assignments) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute(
                "UPDATE " + QUEUE_TABLE + " SET " + assignments
                        + " WHERE id = (SELECT id FROM " + QUEUE_TABLE + " WHERE key = ? ORDER BY key_order LIMIT 1)", key));
    }
}
