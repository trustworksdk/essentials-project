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

package dk.trustworks.essentials.components.foundation.transaction;

import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TransactionProbeTest {
    @AfterEach
    void clearSpringTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void a_spring_transaction_is_reported_active_only_while_one_is() {
        assertThat(TransactionProbe.isSpringTransactionActive()).isFalse();

        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThat(TransactionProbe.isSpringTransactionActive()).isTrue();
    }

    @Test
    void a_missing_factory_counts_as_no_unit_of_work() {
        assertThat(TransactionProbe.isUnitOfWorkActive(null)).isFalse();
    }

    @Test
    void a_factory_with_a_current_unit_of_work_counts_as_active() {
        assertThat(TransactionProbe.isUnitOfWorkActive(factoryWith(mock(UnitOfWork.class)))).isTrue();
        assertThat(TransactionProbe.isUnitOfWorkActive(factoryWith(null))).isFalse();
    }

    @Test
    void a_spring_transaction_with_no_unit_of_work_bound_yet_counts_as_active_for_a_spring_aware_factory() {
        var springFactory = new SpringTransactionAwareJdbiUnitOfWorkFactory(Jdbi.create(() -> {
            throw new SQLException("No database in this test");
        }), mock(PlatformTransactionManager.class));

        assertThat(TransactionProbe.isUnitOfWorkActive(springFactory)).isFalse();

        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(springFactory.getCurrentUnitOfWork())
                .as("what a plain @Transactional/TransactionTemplate looks like before anything joined it")
                .isEmpty();
        assertThat(TransactionProbe.isUnitOfWorkActive(springFactory)).isTrue();
    }

    @Test
    void a_spring_transaction_does_not_count_for_a_factory_that_cannot_join_it() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThat(TransactionProbe.isUnitOfWorkActive(factoryWith(null))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static UnitOfWorkFactory<UnitOfWork> factoryWith(UnitOfWork current) {
        UnitOfWorkFactory<UnitOfWork> factory = mock(UnitOfWorkFactory.class);
        when(factory.getCurrentUnitOfWork()).thenReturn(Optional.ofNullable(current));
        return factory;
    }
}
