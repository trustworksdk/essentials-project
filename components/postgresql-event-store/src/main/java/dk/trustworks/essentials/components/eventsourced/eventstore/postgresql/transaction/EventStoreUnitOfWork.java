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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

import java.util.*;

/**
 * Variant of the {@link UnitOfWork} that allows the {@link EventStore}
 * to register any {@link PersistedEvent}'s persisted during a {@link UnitOfWork},
 * such that these events can be published on the {@link EventStoreEventBus}
 */
public interface EventStoreUnitOfWork extends HandleAwareUnitOfWork {
    /**
     * Register {@link PersistedEvent}'s in the {@link EventStoreUnitOfWork} that will be published during {@link CommitStage#BeforeCommit},
     * {@link CommitStage#AfterCommit} and  {@link CommitStage#AfterRollback}
     *
     * @param eventsPersistedInThisUnitOfWork the {@link PersistedEvent}'s to add
     * @see #removeFlushedEventsPersisted(List)
     * @see #removeFlushedEventPersisted(PersistedEvent)
     */
    void registerEventsPersisted(List<PersistedEvent> eventsPersistedInThisUnitOfWork);

    /**
     * Remove {@link PersistedEvent}'s from the {@link EventStoreUnitOfWork} such that it won't be published during {@link CommitStage#BeforeCommit},
     * {@link CommitStage#AfterCommit} and  {@link CommitStage#AfterRollback}<br>
     * Used by {@link DefaultEventStoreSubscriptionManager#subscribeToAggregateEventsInTransaction(SubscriberId, AggregateType, Optional, TransactionalPersistedEventHandler)}
     * if {@link PersistedEvents#commitStage} is {@link CommitStage#Flush}
     *
     * @param eventsPersistedToRemoveFromThisUnitOfWork the {@link PersistedEvent}'s to remove
     * @see EventStoreSubscriptionManager#subscribeToAggregateEventsInTransaction(SubscriberId, AggregateType, Optional, TransactionalPersistedEventHandler)
     * @see FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream
     */
    void removeFlushedEventsPersisted(List<PersistedEvent> eventsPersistedToRemoveFromThisUnitOfWork);

    /**
     * Remove {@link PersistedEvent} from the {@link EventStoreUnitOfWork} such that it won't be published during {@link CommitStage#BeforeCommit},
     * {@link CommitStage#AfterCommit} and  {@link CommitStage#AfterRollback}<br>
     * Used by {@link DefaultEventStoreSubscriptionManager#subscribeToAggregateEventsInTransaction(SubscriberId, AggregateType, Optional, TransactionalPersistedEventHandler)}
     * if {@link PersistedEvents#commitStage} is {@link CommitStage#Flush}
     *
     * @param eventPersistedToRemoveFromThisUnitOfWork the {@link PersistedEvent} to remove
     * @see EventStoreSubscriptionManager#subscribeToAggregateEventsInTransaction(SubscriberId, AggregateType, Optional, TransactionalPersistedEventHandler)
     * @see FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream
     */
    void removeFlushedEventPersisted(PersistedEvent eventPersistedToRemoveFromThisUnitOfWork);

    /**
     * The total number of {@link PersistedEvent}'s registered through {@link #registerEventsPersisted(List)} in this
     * {@link EventStoreUnitOfWork} - including those since removed through {@link #removeFlushedEventsPersisted(List)}/
     * {@link #removeFlushedEventPersisted(PersistedEvent)}, because they were already published during {@link CommitStage#Flush}.<br>
     * The number never decreases, so two readings that differ tell that events were persisted in between.
     * A registered event is state the {@link EventStoreUnitOfWork} holds in memory and publishes when it commits,
     * so it is not undone by rolling the underlying transaction back to a savepoint.
     * <p>
     * The default implementation throws {@link UnsupportedOperationException}, since an {@link EventStoreUnitOfWork}
     * that doesn't count its events can't answer; callers must treat that as "unknown" and assume events were persisted.
     * All {@link EventStoreUnitOfWork} implementations provided by Essentials override it.
     *
     * @return the number of {@link PersistedEvent}'s registered in this {@link EventStoreUnitOfWork} so far
     * @throws UnsupportedOperationException if this {@link EventStoreUnitOfWork} doesn't count the events registered in it
     */
    default long getNumberOfEventsPersisted() {
        throw new UnsupportedOperationException(getClass().getName() + " doesn't count the PersistedEvent's registered in it");
    }
}
