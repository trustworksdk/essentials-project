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
import dk.trustworks.essentials.components.foundation.messaging.queue.api.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin surface over the split (S3 increment 2).
 *
 * <h2>Why this is a test and not a port</h2>
 * The plan budgeted increment 2 as the expensive half: 12 admin operations, each living in three synced places —
 * the {@code *Api} SPI, {@code EssentialsAdminApiSpec}'s mapping table, and a controller in
 * {@code spring-boot-starter-admin-api}. That turned out not to apply here.
 * {@link DefaultDurableQueuesApi} is written against the {@link DurableQueues} <em>interface</em>, so the split
 * satisfies it by being a {@code DurableQueues} — no new SPI method, no spec entry, no controller. What was left
 * was to establish that claim rather than assert it, and to fix the one operation whose semantics the split really
 * did change: paging.
 *
 * <h2>What is still missing, deliberately</h2>
 * {@link DurableQueuesStatistics} is constructed with <em>a</em> queue table name and the split has two, so this
 * runs with statistics absent — which the API supports, returning an empty {@code getQueuedStatistics}. Teaching
 * statistics about two tables belongs with S2, the statistics rewrite, not here.
 */
@Testcontainers
class PostgresqlSplitDurableQueuesAdminApiIT {

    private static final String BASE_TABLE_NAME = "split_admin_queues";

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("split-admin-db");

    /** Any non-null principal: {@code AllAccessSecurityProvider} grants every role, so authorization is not what is under test. */
    private static final Object PRINCIPAL = "admin";

    private JdbiUnitOfWorkFactory        unitOfWorkFactory;
    private PostgresqlSplitDurableQueues durableQueues;
    private DurableQueuesApi             api;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX);
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX);
        });
        var jsonSerializer = EssentialsObjectMappers.createJSONSerializer();
        durableQueues = PostgresqlSplitDurableQueues.builder()
                                                    .setUnitOfWorkFactory(unitOfWorkFactory)
                                                    .setJsonSerializer(jsonSerializer)
                                                    .setBaseQueueTableName(BASE_TABLE_NAME)
                                                    .build();
        durableQueues.start();
        api = new DefaultDurableQueuesApi(new EssentialsSecurityProvider.AllAccessSecurityProvider(),
                                          durableQueues,
                                          jsonSerializer,
                                          null);
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    /**
     * Every read the admin surface performs, over a queue whose messages are spread across both tables. The
     * by-id ones are the interesting half: an admin operation is given a {@code QueueEntryId} and nothing else, so
     * each has to find its row in whichever table holds it.
     */
    @Test
    void the_admin_api_reads_a_queue_whose_messages_span_both_tables() {
        var queueName   = QueueName.of("AdminReads");
        var unorderedId = durableQueues.queueMessage(queueName, Message.of("plain"));
        var orderedId   = durableQueues.queueMessage(queueName, OrderedMessage.of("ordered", "key-a", 0L));

        assertThat(api.getQueueNames(PRINCIPAL)).contains(queueName);
        assertThat(api.getTotalMessagesQueuedFor(PRINCIPAL, queueName)).isEqualTo(2L);
        assertThat(api.getQueuedMessages(PRINCIPAL, queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10)).hasSize(2);

        // The ordered one lives in the second table the dual lookup tries, so it is the one that fails if the
        // lookup is not really dual.
        assertThat(api.getQueueNameFor(PRINCIPAL, orderedId)).contains(queueName);
        assertThat(api.getQueuedMessage(PRINCIPAL, orderedId)).isPresent();
        assertThat(api.getQueueNameFor(PRINCIPAL, unorderedId)).contains(queueName);
        assertThat(api.getQueuedMessage(PRINCIPAL, unorderedId)).isPresent();

        // Supported, and empty rather than failing, when no DurableQueuesStatistics is wired - see the class note.
        assertThat(api.getQueuedStatistics(PRINCIPAL, queueName)).isEmpty();
    }

    /**
     * The mutating operations, driven against a message in the <b>ordered</b> table specifically: dead-lettering,
     * resurrecting and deleting all address the message by id alone.
     */
    @Test
    void the_admin_api_dead_letters_resurrects_and_deletes_an_ordered_message_by_id() {
        var queueName = QueueName.of("AdminWrites");
        var orderedId = durableQueues.queueMessage(queueName, OrderedMessage.of("ordered", "key-a", 0L));

        assertThat(api.markAsDeadLetterMessage(PRINCIPAL, orderedId)).isPresent();
        assertThat(api.getTotalDeadLetterMessagesQueuedFor(PRINCIPAL, queueName)).isEqualTo(1L);
        assertThat(api.getDeadLetterMessages(PRINCIPAL, queueName, DurableQueues.QueueingSortOrder.ASC, 0, 10)).hasSize(1);

        assertThat(api.resurrectDeadLetterMessage(PRINCIPAL, orderedId, java.time.Duration.ZERO)).isPresent();
        assertThat(api.getTotalDeadLetterMessagesQueuedFor(PRINCIPAL, queueName)).isZero();
        assertThat(api.getTotalMessagesQueuedFor(PRINCIPAL, queueName)).isEqualTo(1L);

        assertThat(api.deleteMessage(PRINCIPAL, orderedId)).isTrue();
        assertThat(api.getTotalMessagesQueuedFor(PRINCIPAL, queueName)).isZero();
    }

    /**
     * {@code purgeQueue} has to clear <b>both</b> tables. A version that purged only one would leave the queue
     * looking half-emptied, and the count the admin UI shows afterwards would be non-zero with no way to act on
     * it.
     */
    @Test
    void purging_a_queue_clears_both_tables() {
        var queueName = QueueName.of("AdminPurge");
        for (var i = 0; i < 4; i++) {
            durableQueues.queueMessage(queueName, Message.of("plain-" + i));
            durableQueues.queueMessage(queueName, OrderedMessage.of("ordered-" + i, "key-" + i, i));
        }

        assertThat(api.purgeQueue(PRINCIPAL, queueName)).isEqualTo(8);
        assertThat(api.getTotalMessagesQueuedFor(PRINCIPAL, queueName)).isZero();
    }

    /**
     * Paging through the admin API, which is the operation the split genuinely changed: it is served from two
     * tables, so the offset cannot be pushed down to either. Asserted here as well as at the SPI level because
     * this is the layer the admin UI actually calls.
     */
    @Test
    void paging_through_the_admin_api_visits_every_message_exactly_once() {
        var queueName = QueueName.of("AdminPaging");
        var expected  = new ArrayList<QueueEntryId>();
        for (var i = 0; i < 17; i++) {
            expected.add(i % 3 == 0
                         ? durableQueues.queueMessage(queueName, Message.of("u" + i))
                         : durableQueues.queueMessage(queueName, OrderedMessage.of("o" + i, "key-" + (i % 4), i)));
        }

        var pageSize = 5;
        var paged    = new ArrayList<QueueEntryId>();
        for (var startIndex = 0; startIndex < expected.size(); startIndex += pageSize) {
            var page = api.getQueuedMessages(PRINCIPAL, queueName, DurableQueues.QueueingSortOrder.ASC, startIndex, pageSize);
            assertThat(page.size()).isLessThanOrEqualTo(pageSize);
            page.forEach(apiQueuedMessage -> paged.add(apiQueuedMessage.id()));
        }

        assertThat(paged).doesNotHaveDuplicates();
        assertThat(paged).containsExactlyInAnyOrderElementsOf(expected);
    }
}
