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
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The by-id operations against the split, counted rather than merely exercised.
 *
 * <h2>Why counting is the point</h2>
 * {@link PostgresqlSplitDurableQueuesIT}'s shared suite already proves these operations produce the right
 * <em>result</em> — it passed while they were implemented by trying one table and then the other. What it cannot
 * see is the cost of that: for a message in the second table, two statements and <b>two runs of the interceptor
 * chain</b>, because interceptors are registered on the composite and on both delegates. An ack-counting or
 * metrics interceptor double-counted, and no assertion anywhere failed.
 * <p>
 * So these tests assert the two things a correct-result test is blind to: <b>exactly one statement</b> and
 * <b>exactly one interceptor invocation</b>, for a message in <em>either</em> table. The ordered cases are the
 * ones that regressed; the unordered cases are the control that keeps the ordered assertions honest, since the
 * unordered table was always tried first and always cost one.
 * <p>
 * {@code retryMessage} in particular is not an error path: {@code CentralizedMessageFetcher} calls it for every
 * {@code markForRedeliveryIn(...)}, which is ordinary control flow for a handler that defers work.
 */
@Testcontainers
class PostgresqlSplitDurableQueuesByIdOperationsIT {

    private static final String BASE_TABLE_NAME = "split_by_id_queues";

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("split-by-id-db");

    private Jdbi                         jdbi;
    private JdbiUnitOfWorkFactory        unitOfWorkFactory;
    private PostgresqlSplitDurableQueues durableQueues;
    private StatementCounter             statementCounter;
    private CountingInterceptor          interceptor;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        statementCounter = new StatementCounter();
        jdbi.setSqlLogger(statementCounter);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX);
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX);
        });
        durableQueues = PostgresqlSplitDurableQueues.builder()
                                                    .setUnitOfWorkFactory(unitOfWorkFactory)
                                                    .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                    .setBaseQueueTableName(BASE_TABLE_NAME)
                                                    .build();
        durableQueues.start();
        interceptor = new CountingInterceptor();
        durableQueues.addInterceptor(interceptor);
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // retryMessage - on the delivery path, via every markForRedeliveryIn(...)
    // ------------------------------------------------------------------------------------------------

    @Test
    void retrying_an_ordered_message_costs_one_statement_and_one_interceptor_invocation() {
        var id = queueOrdered("RetryOrdered");

        var retried = measure(() -> durableQueues.retryMessage(id, new RuntimeException("boom"), Duration.ofSeconds(30)));

        assertThat(retried).isPresent();
        assertThat((Object) retried.get().getId()).isEqualTo(id);
        assertThatCostWasOneStatementAndOne(interceptor.retries);
    }

    @Test
    void retrying_an_unordered_message_costs_one_statement_and_one_interceptor_invocation() {
        var id = queueUnordered("RetryUnordered");

        var retried = measure(() -> durableQueues.retryMessage(id, new RuntimeException("boom"), Duration.ofSeconds(30)));

        assertThat(retried).isPresent();
        assertThat((Object) retried.get().getId()).isEqualTo(id);
        assertThatCostWasOneStatementAndOne(interceptor.retries);
    }

    // ------------------------------------------------------------------------------------------------
    // markAsDeadLetterMessage - also reached from the fetcher, not only from the admin API
    // ------------------------------------------------------------------------------------------------

    @Test
    void dead_lettering_an_ordered_message_costs_one_statement_and_one_interceptor_invocation() {
        var id = queueOrdered("DlqOrdered");

        var deadLettered = measure(() -> durableQueues.markAsDeadLetterMessage(id, new RuntimeException("boom")));

        assertThat(deadLettered).isPresent();
        assertThat(deadLettered.get().isDeadLetterMessage()).isTrue();
        assertThatCostWasOneStatementAndOne(interceptor.deadLetters);
    }

    @Test
    void dead_lettering_an_unordered_message_costs_one_statement_and_one_interceptor_invocation() {
        var id = queueUnordered("DlqUnordered");

        var deadLettered = measure(() -> durableQueues.markAsDeadLetterMessage(id, new RuntimeException("boom")));

        assertThat(deadLettered).isPresent();
        assertThat(deadLettered.get().isDeadLetterMessage()).isTrue();
        assertThatCostWasOneStatementAndOne(interceptor.deadLetters);
    }

    // ------------------------------------------------------------------------------------------------
    // resurrectDeadLetterMessage and deleteMessage - admin surface
    // ------------------------------------------------------------------------------------------------

    @Test
    void resurrecting_an_ordered_dead_letter_costs_one_statement_and_one_interceptor_invocation() {
        var id = queueOrdered("ResurrectOrdered");
        durableQueues.markAsDeadLetterMessage(id, new RuntimeException("boom"));

        var resurrected = measure(() -> durableQueues.resurrectDeadLetterMessage(id, Duration.ofSeconds(1)));

        assertThat(resurrected).isPresent();
        assertThat(resurrected.get().isDeadLetterMessage()).isFalse();
        assertThatCostWasOneStatementAndOne(interceptor.resurrections);
    }

    @Test
    void deleting_an_ordered_message_costs_one_statement_and_one_interceptor_invocation() {
        var id = queueOrdered("DeleteOrdered");

        var deleted = measure(() -> durableQueues.deleteMessage(id));

        assertThat(deleted).isTrue();
        // Before anything else queries the tables - those statements would be counted too
        assertThatCostWasOneStatementAndOne(interceptor.deletions);
        assertThat(durableQueues.getQueuedMessage(id)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------
    // By-id reads - interceptable too, so they double-fired for the same reason
    // ------------------------------------------------------------------------------------------------

    @Test
    void reading_an_ordered_message_by_id_costs_one_statement_and_one_interceptor_invocation() {
        var id = queueOrdered("ReadOrdered");

        var found = measure(() -> durableQueues.getQueuedMessage(id));

        assertThat(found).isPresent();
        assertThatCostWasOneStatementAndOne(interceptor.reads);
    }

    @Test
    void reading_an_ordered_dead_letter_by_id_costs_one_statement_and_one_interceptor_invocation() {
        var id = queueOrdered("ReadOrderedDlq");
        durableQueues.markAsDeadLetterMessage(id, new RuntimeException("boom"));

        var found = measure(() -> durableQueues.getDeadLetterMessage(id));

        assertThat(found).isPresent();
        assertThatCostWasOneStatementAndOne(interceptor.deadLetterReads);
    }

    /**
     * A queued message and a dead letter are the same row distinguished by a flag, so the union has to keep the
     * flag in its predicate rather than merge on id alone.
     */
    @Test
    void a_queued_message_is_not_returned_as_a_dead_letter_and_vice_versa() {
        var queued     = queueOrdered("ReadDistinct");
        var deadLetter = queueUnordered("ReadDistinct");
        durableQueues.markAsDeadLetterMessage(deadLetter, new RuntimeException("boom"));

        assertThat(durableQueues.getQueuedMessage(queued)).isPresent();
        assertThat(durableQueues.getDeadLetterMessage(queued)).isEmpty();
        assertThat(durableQueues.getDeadLetterMessage(deadLetter)).isPresent();
        assertThat(durableQueues.getQueuedMessage(deadLetter)).isEmpty();
    }

    @Test
    void the_queue_name_for_an_ordered_id_is_found_in_one_statement() {
        var id = queueOrdered("QueueNameOrdered");

        var queueName = measure(() -> durableQueues.getQueueNameFor(id));

        assertThat(queueName).contains(QueueName.of("QueueNameOrdered"));
        assertThat(statementCounter.queueTableStatements()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------
    // Misses
    // ------------------------------------------------------------------------------------------------

    /**
     * An id in neither table is the case the old implementation was most expensive for — it paid both attempts
     * before concluding nothing matched. It now costs the same single statement as a hit, and still reports the
     * miss rather than inventing a result.
     */
    @Test
    void an_unknown_id_reports_a_miss_and_still_costs_one_statement() {
        var unknownId = QueueEntryId.of(UUID.randomUUID().toString());

        var retried = measure(() -> durableQueues.retryMessage(unknownId, new RuntimeException("boom"), Duration.ofSeconds(30)));

        assertThat(retried).isEmpty();
        assertThatCostWasOneStatementAndOne(interceptor.retries);
    }

    @Test
    void deleting_an_unknown_id_reports_false_and_still_costs_one_statement() {
        var unknownId = QueueEntryId.of(UUID.randomUUID().toString());

        var deleted = measure(() -> durableQueues.deleteMessage(unknownId));

        assertThat(deleted).isFalse();
        assertThatCostWasOneStatementAndOne(interceptor.deletions);
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private QueueEntryId queueOrdered(String queueName) {
        return durableQueues.queueMessage(QueueName.of(queueName), OrderedMessage.of("payload", "key-a", 0L));
    }

    private QueueEntryId queueUnordered(String queueName) {
        return durableQueues.queueMessage(QueueName.of(queueName), Message.of("payload"));
    }

    /**
     * Counts only the statements the operation itself issues - everything before it is setup.
     */
    private <T> T measure(java.util.function.Supplier<T> operation) {
        statementCounter.reset();
        return operation.get();
    }

    private void assertThatCostWasOneStatementAndOne(AtomicInteger interceptorInvocations) {
        assertThat(statementCounter.queueTableStatements())
                .as("the operation must touch both tables in ONE statement, not one per table")
                .isEqualTo(1);
        assertThat(interceptorInvocations.get())
                .as("the interceptor chain must run once for the operation, not once per delegate attempt")
                .isEqualTo(1);
    }

    private final class StatementCounter implements SqlLogger {
        private final List<String> statements = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void logAfterExecution(StatementContext context) {
            statements.add(context.getRenderedSql());
        }

        void reset() {
            statements.clear();
        }

        /**
         * Transaction control and anything the pool issues are not what is being counted.
         */
        long queueTableStatements() {
            synchronized (statements) {
                return statements.stream()
                                 .filter(sql -> sql.contains(BASE_TABLE_NAME))
                                 .count();
            }
        }
    }

    private static final class CountingInterceptor implements DurableQueuesInterceptor {
        private final AtomicInteger retries         = new AtomicInteger();
        private final AtomicInteger deadLetters     = new AtomicInteger();
        private final AtomicInteger resurrections   = new AtomicInteger();
        private final AtomicInteger deletions       = new AtomicInteger();
        private final AtomicInteger reads           = new AtomicInteger();
        private final AtomicInteger deadLetterReads = new AtomicInteger();

        @Override
        public void setDurableQueues(DurableQueues durableQueues) {
        }

        @Override
        public Optional<QueuedMessage> intercept(GetQueuedMessage operation, InterceptorChain<GetQueuedMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
            reads.incrementAndGet();
            return interceptorChain.proceed();
        }

        @Override
        public Optional<QueuedMessage> intercept(GetDeadLetterMessage operation, InterceptorChain<GetDeadLetterMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
            deadLetterReads.incrementAndGet();
            return interceptorChain.proceed();
        }

        @Override
        public Optional<QueuedMessage> intercept(RetryMessage operation, InterceptorChain<RetryMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
            retries.incrementAndGet();
            return interceptorChain.proceed();
        }

        @Override
        public Optional<QueuedMessage> intercept(MarkAsDeadLetterMessage operation, InterceptorChain<MarkAsDeadLetterMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
            deadLetters.incrementAndGet();
            return interceptorChain.proceed();
        }

        @Override
        public Optional<QueuedMessage> intercept(ResurrectDeadLetterMessage operation, InterceptorChain<ResurrectDeadLetterMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
            resurrections.incrementAndGet();
            return interceptorChain.proceed();
        }

        @Override
        public boolean intercept(DeleteMessage operation, InterceptorChain<DeleteMessage, Boolean, DurableQueuesInterceptor> interceptorChain) {
            deletions.incrementAndGet();
            return interceptorChain.proceed();
        }
    }
}
