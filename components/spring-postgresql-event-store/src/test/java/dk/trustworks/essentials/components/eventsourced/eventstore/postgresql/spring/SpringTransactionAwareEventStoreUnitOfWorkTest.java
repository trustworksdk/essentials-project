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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring.SpringTransactionAwareEventStoreUnitOfWorkFactory.SpringTransactionAwareEventStoreUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the {@link SpringTransactionAwareEventStoreUnitOfWork} copies of {@code getNumberOfEventsPersisted()} and
 * {@link UnitOfWork#hasLifecycleCallbackResourcesWithPendingChanges()} - the cases {@code EventStoreManagedUnitOfWorkTest} and
 * {@code GenericHandleAwareUnitOfWorkTest} cover for the non-Spring implementations. {@code ViewEventProcessor} decides between
 * queueing a failed event and escalating it on these two signals, and Spring Boot applications run on this implementation.
 * <p>
 * Registering events and resources doesn't need the underlying transaction, so neither a database nor a real transaction manager is involved.
 */
class SpringTransactionAwareEventStoreUnitOfWorkTest {
    private final SpringTransactionAwareEventStoreUnitOfWorkFactory unitOfWorkFactory = new SpringTransactionAwareEventStoreUnitOfWorkFactory(Jdbi.create(() -> {
        throw new SQLException("No database in this test");
    }), mock(PlatformTransactionManager.class));

    @Test
    void no_events_are_persisted_in_a_new_unit_of_work() {
        var unitOfWork = new SpringTransactionAwareEventStoreUnitOfWork(unitOfWorkFactory);

        assertThat(unitOfWork.getNumberOfEventsPersisted()).isZero();
    }

    @Test
    void every_event_registered_is_counted() {
        var unitOfWork = new SpringTransactionAwareEventStoreUnitOfWork(unitOfWorkFactory);

        unitOfWork.registerEventsPersisted(List.of(mock(PersistedEvent.class), mock(PersistedEvent.class)));
        unitOfWork.registerEventsPersisted(List.of(mock(PersistedEvent.class)));

        assertThat(unitOfWork.getNumberOfEventsPersisted()).isEqualTo(3);
    }

    /**
     * An in-transaction subscription removes the events it received during {@code CommitStage.Flush} - they were
     * persisted all the same, so the number must not go down
     */
    @Test
    void removing_flushed_events_does_not_decrease_the_number() {
        var unitOfWork = new SpringTransactionAwareEventStoreUnitOfWork(unitOfWorkFactory);
        var first      = mock(PersistedEvent.class);
        var second     = mock(PersistedEvent.class);
        unitOfWork.registerEventsPersisted(List.of(first, second));

        unitOfWork.removeFlushedEventPersisted(first);
        unitOfWork.removeFlushedEventsPersisted(List.of(second));

        assertThat(unitOfWork.getNumberOfEventsPersisted()).isEqualTo(2);
    }

    @Test
    void a_unit_of_work_without_resources_has_no_pending_changes() {
        var unitOfWork = new SpringTransactionAwareEventStoreUnitOfWork(unitOfWorkFactory);

        assertThat(unitOfWork.hasLifecycleCallbackResourcesWithPendingChanges()).isFalse();
    }

    @Test
    void resources_whose_callback_reports_no_pending_changes_are_not_pending_changes() {
        var unitOfWork = new SpringTransactionAwareEventStoreUnitOfWork(unitOfWorkFactory);
        var callback   = new PendingWhenStartsWithChangedCallback();
        unitOfWork.registerLifecycleCallbackForResource("loaded", callback);
        unitOfWork.registerLifecycleCallbackForResource("also loaded", callback);

        assertThat(unitOfWork.hasLifecycleCallbackResourcesWithPendingChanges()).isFalse();
    }

    @Test
    void a_single_resource_with_pending_changes_is_enough() {
        var unitOfWork = new SpringTransactionAwareEventStoreUnitOfWork(unitOfWorkFactory);
        var callback   = new PendingWhenStartsWithChangedCallback();
        unitOfWork.registerLifecycleCallbackForResource("loaded", callback);
        unitOfWork.registerLifecycleCallbackForResource("changed", callback);

        assertThat(unitOfWork.hasLifecycleCallbackResourcesWithPendingChanges()).isTrue();
    }

    @Test
    void a_callback_that_does_not_override_hasPendingChanges_counts_every_resource_as_pending() {
        var unitOfWork = new SpringTransactionAwareEventStoreUnitOfWork(unitOfWorkFactory);
        unitOfWork.registerLifecycleCallbackForResource("loaded", new PendingWhenStartsWithChangedCallback());
        unitOfWork.registerLifecycleCallbackForResource("unknown", new NoOpCallback<String>());

        assertThat(unitOfWork.hasLifecycleCallbackResourcesWithPendingChanges()).isTrue();
    }

    private static class PendingWhenStartsWithChangedCallback extends NoOpCallback<String> {
        @Override
        public boolean hasPendingChanges(String resource) {
            return resource.startsWith("changed");
        }
    }

    private static class NoOpCallback<T> implements UnitOfWorkLifecycleCallback<T> {
        @Override
        public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<T> associatedResources) {
            return BeforeCommitProcessingStatus.COMPLETED;
        }

        @Override
        public void afterCommit(UnitOfWork unitOfWork, List<T> associatedResources) {
        }

        @Override
        public void beforeRollback(UnitOfWork unitOfWork, List<T> associatedResources, Throwable causeOfTheRollback) {
        }

        @Override
        public void afterRollback(UnitOfWork unitOfWork, List<T> associatedResources, Throwable causeOfTheRollback) {
        }
    }
}
