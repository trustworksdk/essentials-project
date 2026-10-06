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

import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The PostgreSQL half of the pair pinned by {@code MongoDurableQueuesBuilderDefaultsTest}. These defaults are not an
 * implementation detail: an application that swaps database module gets the same delivery semantics only for as long
 * as the two builders agree.
 * <p>
 * The transactional-mode assertions these tests used to carry are gone with {@code TransactionalMode} itself — every
 * queue operation now runs in its own transaction, so there is no longer a choice to diverge on.
 */
class PostgresqlDurableQueuesBuilderDefaultsTest {

    @SuppressWarnings("unchecked")
    private static PostgresqlDurableQueues minimalBuild() {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(mock(HandleAwareUnitOfWorkFactory.class))
                                      .build();
    }


    @Test
    void test_the_default_message_handling_timeout_is_thirty_seconds() {
        assertThat(PostgresqlDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void test_the_builder_needs_nothing_but_a_unitOfWorkFactory() {
        assertThat(minimalBuild()).isNotNull();
    }

}
