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
package dk.trustworks.essentials.components.foundation.transaction.spring;

import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The isolation level the transactions of a {@link SpringTransactionAwareUnitOfWorkFactory} ask for. READ COMMITTED
 * unless changed; {@link UnitOfWorkTransactionIsolation#DATABASE_DEFAULT} asks for none, which spares Spring's
 * isolation-level query at the start of every transaction.
 */
class SpringTransactionAwareUnitOfWorkFactoryIsolationTest {
    private final SpringTransactionAwareJdbiUnitOfWorkFactory unitOfWorkFactory = new SpringTransactionAwareJdbiUnitOfWorkFactory(Jdbi.create(() -> {
        throw new SQLException("No database in this test");
    }), mock(PlatformTransactionManager.class));

    @Test
    void transactions_ask_for_read_committed_unless_changed() {
        assertThat(unitOfWorkFactory.defaultTransactionDefinition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void database_default_asks_for_no_isolation_level() {
        unitOfWorkFactory.setTransactionIsolation(UnitOfWorkTransactionIsolation.DATABASE_DEFAULT);

        assertThat(unitOfWorkFactory.defaultTransactionDefinition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        assertThat(unitOfWorkFactory.defaultTransactionDefinition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void read_committed_can_be_set_back() {
        unitOfWorkFactory.setTransactionIsolation(UnitOfWorkTransactionIsolation.DATABASE_DEFAULT);
        unitOfWorkFactory.setTransactionIsolation(UnitOfWorkTransactionIsolation.READ_COMMITTED);

        assertThat(unitOfWorkFactory.defaultTransactionDefinition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void no_isolation_is_refused() {
        assertThatThrownBy(() -> unitOfWorkFactory.setTransactionIsolation(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
