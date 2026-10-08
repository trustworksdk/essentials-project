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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnqueueOutsideUnitOfWorkGuardTest {
    private static final QueueName QUEUE = QueueName.of("orders");

    @AfterEach
    void clearSpringTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void by_default_an_enqueue_outside_a_unit_of_work_goes_ahead() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class);

        assertThat(guard.isRequireActiveUnitOfWork()).isFalse();
        assertThatCode(() -> guard.checkEnqueue(QUEUE, factoryWith(null))).doesNotThrowAnyException();
        assertThatCode(() -> guard.checkEnqueue(QUEUE, factoryWith(null))).as("and again - the WARN is once, never a throw").doesNotThrowAnyException();
    }

    @Test
    void when_required_an_enqueue_outside_a_unit_of_work_fails_and_names_the_property() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class);
        guard.setRequireActiveUnitOfWork(true);

        assertThatThrownBy(() -> guard.checkEnqueue(QUEUE, factoryWith(null)))
                .isInstanceOf(NoActiveUnitOfWorkException.class)
                .hasMessageContaining("orders")
                .hasMessageContaining(EnqueueOutsideUnitOfWorkGuard.REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY);
    }

    @Test
    void when_required_a_missing_factory_counts_as_outside_a_unit_of_work() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class);
        guard.setRequireActiveUnitOfWork(true);

        assertThatThrownBy(() -> guard.checkEnqueue(QUEUE, (UnitOfWorkFactory<? extends UnitOfWork>) null))
                .isInstanceOf(NoActiveUnitOfWorkException.class);
    }

    @Test
    void when_required_an_enqueue_inside_a_unit_of_work_goes_ahead() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class);
        guard.setRequireActiveUnitOfWork(true);

        assertThatCode(() -> guard.checkEnqueue(QUEUE, factoryWith(mock(UnitOfWork.class)))).doesNotThrowAnyException();
    }

    @Test
    void a_spring_transaction_with_no_unit_of_work_bound_yet_counts_as_active_for_a_spring_aware_factory() {
        var springFactory = new SpringTransactionAwareJdbiUnitOfWorkFactory(Jdbi.create(() -> {
            throw new SQLException("No database in this test");
        }), mock(PlatformTransactionManager.class));

        assertThat(EnqueueOutsideUnitOfWorkGuard.isUnitOfWorkActive(springFactory)).isFalse();

        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(springFactory.getCurrentUnitOfWork())
                .as("what a plain @Transactional/TransactionTemplate looks like before anything joined it")
                .isEmpty();
        assertThat(EnqueueOutsideUnitOfWorkGuard.isUnitOfWorkActive(springFactory)).isTrue();
    }

    @Test
    void a_spring_transaction_does_not_count_for_a_factory_that_cannot_join_it() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThat(EnqueueOutsideUnitOfWorkGuard.isUnitOfWorkActive(factoryWith(null))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static UnitOfWorkFactory<UnitOfWork> factoryWith(UnitOfWork current) {
        UnitOfWorkFactory<UnitOfWork> factory = mock(UnitOfWorkFactory.class);
        when(factory.getCurrentUnitOfWork()).thenReturn(Optional.ofNullable(current));
        return factory;
    }
}
