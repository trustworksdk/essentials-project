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

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Pins the second half of a two-part defect: resurrecting an <b>ordered</b> dead-letter message.
 *
 * <h2>Why this had no coverage</h2>
 * {@code resurrectDeadLetterMessage} logs whether the resurrected message was ordered, and to do so it used to
 * cast the {@link QueuedMessage} itself to {@link OrderedMessage} — a cast that can never succeed, since a
 * {@code QueuedMessage} <em>wraps</em> a message rather than being one. It never threw only because
 * {@code DefaultQueuedMessage.getDeliveryMode()} returned {@code NORMAL} unconditionally, so the branch
 * containing it was unreachable.
 * <p>
 * The two defects therefore concealed each other: fixing the accessor alone would have converted a dormant bug
 * into a {@code ClassCastException} on every resurrection of an ordered dead letter, and fixing the cast alone
 * would have left the branch unreachable and the fix unverifiable. They are fixed together, and this test is
 * what demonstrates the pair — it exercises the exact path that had no test.
 * <p>
 * Note that the log level is irrelevant to the hazard: the cast sits in an argument expression of a
 * parameterised SLF4J call, and those are evaluated eagerly before the call regardless of whether the level is
 * enabled. A cast hidden in a {@code log.debug(...)} argument is therefore just as live as one in ordinary code —
 * which is exactly why this needed a test rather than an inspection.
 */
@Testcontainers
class PostgresqlOrderedDeadLetterResurrectionIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("ordered-dlq-resurrect-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                  .execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void an_ordered_dead_letter_message_can_be_resurrected_and_reports_its_key_and_order() {
        var queueName = QueueName.of("OrderedDeadLetterResurrection");

        var queueEntryId = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.queueMessageAsDeadLetterMessage(
                queueName,
                OrderedMessage.of("ordered-payload", "key-a", 3L),
                new RuntimeException("seeded as a dead letter")));

        var resurrected = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.resurrectDeadLetterMessage(queueEntryId, Duration.ofMillis(1)));

        assertThat(resurrected).isPresent();
        assertThat(resurrected.get().isDeadLetterMessage()).isFalse();
        // The mode is now derived from the wrapped message, so it agrees with what was persisted.
        assertThat(resurrected.get().getDeliveryMode()).isEqualTo(QueuedMessage.DeliveryMode.IN_ORDER);
        assertThat(resurrected.get().getMessage()).isInstanceOf(OrderedMessage.class);
        var orderedMessage = (OrderedMessage) resurrected.get().getMessage();
        assertThat(orderedMessage.getKey()).isEqualTo("key-a");
        assertThat(orderedMessage.getOrder()).isEqualTo(3L);

        // And it is genuinely back in the queue rather than merely reported as resurrected.
        var next = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getNextMessageReadyForDelivery(queueName));
        assertThat(next).isPresent();
        // Cast to CharSequence to disambiguate the AssertJ overloads - QueueEntryId is a CharSequenceType, so the
        // generic and the CharSequence assertThat both match. Same pattern as SingleOperationTransactionPostgresqlDurableQueuesIT.
        assertThat((CharSequence) next.get().getId()).isEqualTo(queueEntryId);
    }

    /**
     * The unordered path for comparison, so a regression that broke ordered resurrection by special-casing it
     * cannot pass by breaking both.
     */
    @Test
    void an_unordered_dead_letter_message_still_resurrects_and_reports_NORMAL() {
        var queueName = QueueName.of("UnorderedDeadLetterResurrection");

        var queueEntryId = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.queueMessageAsDeadLetterMessage(
                queueName,
                Message.of("plain-payload"),
                new RuntimeException("seeded as a dead letter")));

        var resurrected = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.resurrectDeadLetterMessage(queueEntryId, Duration.ofMillis(1)));

        assertThat(resurrected).isPresent();
        assertThat(resurrected.get().getDeliveryMode()).isEqualTo(QueuedMessage.DeliveryMode.NORMAL);
        assertThat(resurrected.get().getMessage()).isNotInstanceOf(OrderedMessage.class);
    }
}
