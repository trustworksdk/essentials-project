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
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link OrderedMessageDuplicateStrategy}: two {@link OrderedMessage}s sharing a key <em>and</em> an order
 * never block each other in the per-key barrier, which only blocks on a <b>strictly</b> lower
 * {@code key_order} — so that key's ordering guarantee silently does not hold. Nothing in the schema prevented
 * it before this setting existed.
 * <p>
 * {@code REJECT} is the default because every ordered message the framework itself produces is duplicate-free by
 * construction: the event processors and the subscription manager key on the aggregate id and order by
 * {@code EventOrder}, which is unique within its stream. The exposure is application code deriving the order from
 * something not unique.
 */
@Testcontainers
class PostgresqlOrderedMessageDuplicateStrategyIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("ordered-duplicate-strategy-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                  .execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void the_default_strategy_rejects_a_duplicate_key_and_order() {
        durableQueues = start(OrderedMessageDuplicateStrategy.REJECT);
        var queueName = QueueName.of("DuplicateReject");

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessage(queueName, OrderedMessage.of("first", "key-a", 0L)));

        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessage(queueName, OrderedMessage.of("second", "key-a", 0L))))
                .as("a second ordered message with the same key and order must not be accepted")
                .isNotNull();

        long queued = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getTotalMessagesQueuedFor(queueName));
        assertThat(queued).isEqualTo(1L);
    }

    /**
     * The same key at a <em>different</em> order, and a different key at the same order, must both still be
     * accepted — otherwise the index is too broad and ordinary ordered traffic breaks.
     */
    @Test
    void the_default_strategy_still_accepts_the_same_key_at_other_orders_and_other_keys_at_the_same_order() {
        durableQueues = start(OrderedMessageDuplicateStrategy.REJECT);
        var queueName = QueueName.of("DuplicateRejectHappyPath");

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            durableQueues.queueMessage(queueName, OrderedMessage.of("a0", "key-a", 0L));
            durableQueues.queueMessage(queueName, OrderedMessage.of("a1", "key-a", 1L));
            durableQueues.queueMessage(queueName, OrderedMessage.of("b0", "key-b", 0L));
        });

        long queued = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getTotalMessagesQueuedFor(queueName));
        assertThat(queued).isEqualTo(3L);
    }

    /**
     * Unordered messages all carry a NULL key and a constant {@code key_order} of -1, so a unique index that did
     * not exclude them would reject the second unordered message ever queued. The index is partial on
     * {@code key IS NOT NULL} for exactly this reason, and that is worth pinning rather than trusting.
     */
    @Test
    void unordered_messages_are_unaffected_by_the_unique_index() {
        durableQueues = start(OrderedMessageDuplicateStrategy.REJECT);
        var queueName = QueueName.of("DuplicateRejectUnordered");

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            for (var i = 0; i < 50; i++) {
                durableQueues.queueMessage(queueName, Message.of("plain-" + i));
            }
        });

        long queued = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getTotalMessagesQueuedFor(queueName));
        assertThat(queued).isEqualTo(50L);
    }

    @Test
    void ALLOW_keeps_the_previous_behaviour_of_accepting_duplicates() {
        durableQueues = start(OrderedMessageDuplicateStrategy.ALLOW);
        var queueName = QueueName.of("DuplicateAllow");

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            durableQueues.queueMessage(queueName, OrderedMessage.of("first", "key-a", 0L));
            durableQueues.queueMessage(queueName, OrderedMessage.of("second", "key-a", 0L));
        });

        // Both accepted - and ordering does not hold for key-a, which is the documented consequence.
        long queued = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getTotalMessagesQueuedFor(queueName));
        assertThat(queued).isEqualTo(2L);
    }

    /**
     * The migration hazard, and the reason this needed a test rather than a code review: {@code CREATE UNIQUE
     * INDEX} cannot succeed against a table that already contains duplicates. An existing deployment upgrading
     * into {@code REJECT} must fail loudly at startup with something actionable — the tempting alternative of
     * logging a warning and continuing would leave it believing ordering is protected when it is not.
     */
    @Test
    void starting_with_REJECT_on_a_table_that_already_contains_duplicates_fails_loudly() {
        // Seed duplicates through a permissive instance, then stop it - this is the pre-upgrade state.
        var permissive = start(OrderedMessageDuplicateStrategy.ALLOW);
        var queueName  = QueueName.of("DuplicatePreExisting");
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            durableQueues.queueMessage(queueName, OrderedMessage.of("first", "key-dup", 5L));
            durableQueues.queueMessage(queueName, OrderedMessage.of("second", "key-dup", 5L));
        });
        permissive.stop();
        durableQueues = null;

        // Bootstrap runs inside a UnitOfWork, so the failure surfaces wrapped - the root cause is what carries
        // the diagnostic an operator needs.
        assertThatThrownBy(() -> start(OrderedMessageDuplicateStrategy.REJECT))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique ordered-message index")
                // The message must name the offending key so an operator can act on it without writing SQL,
                // and must name the escape hatch.
                .hasMessageContaining("key-dup")
                .hasMessageContaining("ALLOW");
    }

    private PostgresqlDurableQueues start(OrderedMessageDuplicateStrategy strategy) {
        var queues = PostgresqlDurableQueues.builder()
                                            .setUnitOfWorkFactory(unitOfWorkFactory)
                                            .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                            .setOrderedMessageDuplicateStrategy(strategy)
                                            .build();
        queues.start();
        durableQueues = queues;
        return queues;
    }
}
