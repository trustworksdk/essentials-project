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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies {@link EventStoreManagedUnitOfWork#getNumberOfEventsPersisted()} - registering events doesn't need the
 * underlying transaction, so no database is involved.
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
}
