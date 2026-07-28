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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.postgresql.InvalidTableOrColumnNameException;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PostgresqlDurableSubscriptionRepositoryTest {

    @Test
    void test_with_invalid_durableSubscriptionsTableName() {
        assertThatThrownBy(() -> {
            new PostgresqlDurableSubscriptionRepository(mock(Jdbi.class),
                                                        mock(EventStore.class),
                                                        "invalid name");
        }).isInstanceOf(InvalidTableOrColumnNameException.class)
          .hasMessageContaining("Invalid table or column name");

        assertThatThrownBy(() -> {
            new PostgresqlDurableSubscriptionRepository(mock(Jdbi.class),
                                                        mock(EventStore.class),
                                                        "name; DROP TABLE users;");
        }).isInstanceOf(InvalidTableOrColumnNameException.class)
          .hasMessageContaining("Invalid table or column name");

        assertThatThrownBy(() -> {
            new PostgresqlDurableSubscriptionRepository(mock(Jdbi.class),
                                                        mock(EventStore.class),
                                                        "drop");
        }).isInstanceOf(InvalidTableOrColumnNameException.class)
          .hasMessageContaining("Invalid table or column name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveResumePoints_does_not_open_a_transaction_when_nothing_changed() {
        var eventStore = mock(EventStore.class);
        EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> uowFactory = mock(EventStoreUnitOfWorkFactory.class);
        doReturn(uowFactory).when(eventStore).getUnitOfWorkFactory();

        var repository = new PostgresqlDurableSubscriptionRepository(mock(Jdbi.class), eventStore);
        // The constructor opens a UoW for table-DDL bootstrap (a no-op on the mock); ignore that so we
        // assert only what saveResumePoints itself does.
        clearInvocations(uowFactory);

        // A freshly-constructed resume point is unchanged (changed=false).
        var unchanged = new SubscriptionResumePoint(SubscriberId.of("sub-1"),
                                                    AggregateType.of("Orders"),
                                                    GlobalEventOrder.of(1),
                                                    OffsetDateTime.now());
        assertThat(unchanged.isChanged()).isFalse();

        repository.saveResumePoints(List.of(unchanged));

        // Optimization: the isChanged() filter runs OUTSIDE the UnitOfWork, so an all-unchanged batch
        // must not touch the factory at all (previously it opened a UoW and executed an empty batch).
        // verifyNoInteractions inspects recorded invocations without itself calling the (default)
        // usingUnitOfWork method on the mock.
        verifyNoInteractions(uowFactory);
    }
}