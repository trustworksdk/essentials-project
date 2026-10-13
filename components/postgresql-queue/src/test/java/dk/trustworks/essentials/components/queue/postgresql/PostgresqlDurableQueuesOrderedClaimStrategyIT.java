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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ordered-claim statements - the default and the head scan - claim the same messages, and each queue is
 * switched to the one that is cheap for its backlog.
 */
@Testcontainers
class PostgresqlDurableQueuesOrderedClaimStrategyIT {
    private static final String    QUEUE_TABLE = "ordered_claim_queue";
    private static final QueueName QUEUE       = QueueName.of("OrderedClaimQueue");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("ordered-claim-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                   postgreSQLContainer.getUsername(),
                                                                   postgreSQLContainer.getPassword()));
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .build();
        durableQueues.purgeQueue(QUEUE);
        // Registered but never started: the tests drive the claims themselves, and fetching requires a consumer
        durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                       .setQueueName(QUEUE)
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
        for (var offset = 0; offset < messages; offset += 1_000) {
            var batch = new ArrayList<Message>(1_000);
            for (var i = offset; i < Math.min(messages, offset + 1_000); i++) {
                batch.add(messageFactory.apply(i));
            }
            unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(QUEUE, batch));
        }
    }

    private void markHead(String key, String assignments) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute(
                "UPDATE " + QUEUE_TABLE + " SET " + assignments
                        + " WHERE id = (SELECT id FROM " + QUEUE_TABLE + " WHERE key = ? ORDER BY key_order LIMIT 1)", key));
    }
}
