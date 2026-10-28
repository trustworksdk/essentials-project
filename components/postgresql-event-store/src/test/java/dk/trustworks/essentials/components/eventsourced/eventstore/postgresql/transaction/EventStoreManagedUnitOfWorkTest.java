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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Verifies {@link EventStoreManagedUnitOfWork#getNumberOfEventsPersisted()} and how the persisted events reach the
 * {@link PersistedEventsCommitLifecycleCallback}s - neither needs the underlying transaction, so no database is involved.
 */
class EventStoreManagedUnitOfWorkTest {
    private final EventStoreManagedUnitOfWorkFactory unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(Jdbi.create(() -> {
        throw new SQLException("No database in this test");
    }));

    @Test
    void no_events_are_persisted_in_a_new_unit_of_work() {
        var unitOfWork = new EventStoreManagedUnitOfWork(unitOfWorkFactory, List.of());

        assertThat(unitOfWork.getNumberOfEventsPersisted()).isZero();
    }

    @Test
    void every_event_registered_is_counted() {
        var unitOfWork = new EventStoreManagedUnitOfWork(unitOfWorkFactory, List.of());

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
        var unitOfWork = new EventStoreManagedUnitOfWork(unitOfWorkFactory, List.of());
        var first      = mock(PersistedEvent.class);
        var second     = mock(PersistedEvent.class);
        unitOfWork.registerEventsPersisted(List.of(first, second));

        unitOfWork.removeFlushedEventPersisted(first);
        unitOfWork.removeFlushedEventsPersisted(List.of(second));

        assertThat(unitOfWork.getNumberOfEventsPersisted()).isEqualTo(2);
    }

    /**
     * A failure after a completed {@code beforeCommit} round (a later round, or the commit itself) rolls back events
     * that were already moved on to the after-commit list. They used to be published as committed
     */
    @Test
    void events_of_a_rolled_back_unit_of_work_are_reported_as_rolled_back_never_as_committed() {
        var callback   = new RecordingCallback();
        var unitOfWork = new EventStoreManagedUnitOfWork(unitOfWorkFactory, List.of(callback));
        var first      = mock(PersistedEvent.class);
        var second     = mock(PersistedEvent.class);
        unitOfWork.registerEventsPersisted(List.of(first));
        unitOfWork.beforeCommitting();
        unitOfWork.registerEventsPersisted(List.of(second));

        unitOfWork.afterRollback(new IllegalStateException("commit failed"));

        assertThat(callback.committed).isEmpty();
        assertThat(callback.rolledBack).containsExactly(List.of(first, second));
    }

    @Test
    void an_error_from_one_after_commit_callback_does_not_keep_the_events_from_the_next() {
        var failing    = new RecordingCallback() {
            @Override
            public void afterCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
                throw new AssertionError("failing callback");
            }
        };
        var recording  = new RecordingCallback();
        var unitOfWork = new EventStoreManagedUnitOfWork(unitOfWorkFactory, List.of(failing, recording));
        var event      = mock(PersistedEvent.class);
        unitOfWork.registerEventsPersisted(List.of(event));
        unitOfWork.beforeCommitting();

        unitOfWork.afterCommitting();

        assertThat(recording.committed).containsExactly(List.of(event));
    }

    @Test
    void a_critical_error_from_an_after_commit_callback_propagates() {
        var failing    = new RecordingCallback() {
            @Override
            public void afterCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
                throw new OutOfMemoryError("critical");
            }
        };
        var unitOfWork = new EventStoreManagedUnitOfWork(unitOfWorkFactory, List.of(failing));
        unitOfWork.beforeCommitting();

        assertThatThrownBy(unitOfWork::afterCommitting).isInstanceOf(OutOfMemoryError.class);
    }

    private static class RecordingCallback implements PersistedEventsCommitLifecycleCallback {
        final List<List<PersistedEvent>> committed  = new ArrayList<>();
        final List<List<PersistedEvent>> rolledBack = new ArrayList<>();

        @Override
        public void beforeCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
        }

        @Override
        public void afterCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
            committed.add(List.copyOf(persistedEvents));
        }

        @Override
        public void afterRollback(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
            rolledBack.add(List.copyOf(persistedEvents));
        }
    }
}
