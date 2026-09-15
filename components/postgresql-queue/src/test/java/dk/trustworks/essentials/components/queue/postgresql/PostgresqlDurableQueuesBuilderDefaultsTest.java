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

import dk.trustworks.essentials.components.foundation.messaging.queue.TransactionalMode;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The PostgreSQL half of the pair pinned by {@code MongoDurableQueuesBuilderDefaultsTest}. Neither default is an
 * implementation detail: an application that swaps database module gets the same delivery semantics only for as long as
 * the two builders agree, and the integration suites branch on {@code getTransactionalMode()} rather than asserting it,
 * so they pass whichever way it drifts.
 * <p>
 * {@link TransactionalMode#SingleOperationTransaction} is the converged value because
 * {@link TransactionalMode#FullyTransactional} is the side documented as broken for retries and dead-lettering.
 */
class PostgresqlDurableQueuesBuilderDefaultsTest {

    @SuppressWarnings("unchecked")
    private static PostgresqlDurableQueues minimalBuild() {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(mock(HandleAwareUnitOfWorkFactory.class))
                                      .build();
    }

    @Test
    void test_the_default_transactional_mode_is_SingleOperationTransaction() {
        assertThat(minimalBuild().getTransactionalMode())
                .as("PostgresqlDurableQueues.builder() and MongoDurableQueues.builder() must agree on the default "
                            + "TransactionalMode; a divergence means the same application code gets different delivery "
                            + "semantics per database")
                .isEqualTo(TransactionalMode.SingleOperationTransaction);
    }

    @Test
    void test_the_default_message_handling_timeout_is_thirty_seconds() {
        assertThat(PostgresqlDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void test_the_default_mode_needs_nothing_but_a_unitOfWorkFactory() {
        assertThat(minimalBuild()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_FullyTransactional_remains_available_and_is_honoured() {
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(mock(HandleAwareUnitOfWorkFactory.class))
                                                   .setTransactionalMode(TransactionalMode.FullyTransactional)
                                                   .build();

        assertThat(durableQueues.getTransactionalMode()).isEqualTo(TransactionalMode.FullyTransactional);
    }
}
