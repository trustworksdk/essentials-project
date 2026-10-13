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
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.GenericHandleAwareUnitOfWorkFactory;
import org.slf4j.*;

import java.util.*;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

final class EventStoreManagedUnitOfWork extends GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork implements EventStoreUnitOfWork {
    private final Logger                                       log = LoggerFactory.getLogger(EventStoreManagedUnitOfWork.class);
    /**
     * The list is maintained by the {@link EventStoreManagedUnitOfWorkFactory} and provided in the constructor
     */
    private final List<PersistedEventsCommitLifecycleCallback> lifecycleCallbacks;
    private final List<PersistedEvent>                         beforeCommitEventsPersisted;
    private final List<PersistedEvent>                         afterCommitEventsPersisted;
    private       long                                         numberOfEventsPersisted;

    public EventStoreManagedUnitOfWork(GenericHandleAwareUnitOfWorkFactory<?> unitOfWorkFactory, List<PersistedEventsCommitLifecycleCallback> lifecycleCallbacks) {
        super(unitOfWorkFactory);
        this.lifecycleCallbacks = requireNonNull(lifecycleCallbacks, "No lifecycleCallbacks provided");
        this.beforeCommitEventsPersisted = new ArrayList<>();
        this.afterCommitEventsPersisted = new ArrayList<>();
    }

    @Override
    public void registerEventsPersisted(List<PersistedEvent> eventsPersistedInThisUnitOfWork) {
        requireNonNull(eventsPersistedInThisUnitOfWork, "No eventsPersistedInThisUnitOfWork provided");
        this.beforeCommitEventsPersisted.addAll(eventsPersistedInThisUnitOfWork);
        numberOfEventsPersisted += eventsPersistedInThisUnitOfWork.size();
    }

    @Override
    public void removeFlushedEventsPersisted(List<PersistedEvent> eventsPersistedToRemoveFromThisUnitOfWork) {
        requireNonNull(eventsPersistedToRemoveFromThisUnitOfWork, "No eventsPersistedToRemoveFromThisUnitOfWork provided");
        this.beforeCommitEventsPersisted.removeAll(eventsPersistedToRemoveFromThisUnitOfWork);
    }

    @Override
    public void removeFlushedEventPersisted(PersistedEvent eventPersistedToRemoveFromThisUnitOfWork) {
        requireNonNull(eventPersistedToRemoveFromThisUnitOfWork, "No eventPersistedToRemoveFromThisUnitOfWork provided");
        this.beforeCommitEventsPersisted.remove(eventPersistedToRemoveFromThisUnitOfWork);
    }

    @Override
    public long getNumberOfEventsPersisted() {
        return numberOfEventsPersisted;
    }

    @Override
    protected void beforeCommitting() {
        for (PersistedEventsCommitLifecycleCallback callback : lifecycleCallbacks) {
            try {
                log.trace("BeforeCommit PersistedEvents for {} with {} persisted events", callback.getClass().getName(), beforeCommitEventsPersisted.size());
                callback.beforeCommit(this, beforeCommitEventsPersisted);
            } catch (RuntimeException e) {
                UnitOfWorkException unitOfWorkException = new UnitOfWorkException(msg("{} failed during beforeCommit PersistedEvents", callback.getClass().getName()), e);
                unitOfWorkException.fillInStackTrace();
                rollback(unitOfWorkException);
                throw unitOfWorkException;
            }
        }
        afterCommitEventsPersisted.addAll(beforeCommitEventsPersisted);
        beforeCommitEventsPersisted.clear();
    }


    @Override
    protected void afterCommitting() {
        for (PersistedEventsCommitLifecycleCallback callback : lifecycleCallbacks) {
            try {
                log.trace("AfterCommit PersistedEvents for {} with {} persisted events", callback.getClass().getName(), afterCommitEventsPersisted.size());
                callback.afterCommit(this, afterCommitEventsPersisted);
            } catch (Throwable e) {
                // The transaction has committed: one failing callback must not keep the events from the others
                rethrowIfCriticalError(e);
                log.error(msg("Failed {} failed during afterCommit PersistedEvents", callback.getClass().getName()), e);
            }
        }
        afterCommitEventsPersisted.clear();
    }

    /**
     * The events persisted in this unit of work were rolled back, including those a completed {@code beforeCommit}
     * round had already moved on to the after-commit list, so they are reported as rolled back and never as committed
     */
    @Override
    protected void afterRollback(Throwable cause) {
        var rolledBackEvents = new ArrayList<PersistedEvent>(afterCommitEventsPersisted.size() + beforeCommitEventsPersisted.size());
        rolledBackEvents.addAll(afterCommitEventsPersisted);
        rolledBackEvents.addAll(beforeCommitEventsPersisted);
        afterCommitEventsPersisted.clear();
        beforeCommitEventsPersisted.clear();
        for (PersistedEventsCommitLifecycleCallback callback : lifecycleCallbacks) {
            try {
                log.trace("AfterRollback PersistedEvents for {} with {} persisted events", callback.getClass().getName(), rolledBackEvents.size());
                callback.afterRollback(this, rolledBackEvents);
            } catch (Throwable e) {
                rethrowIfCriticalError(e);
                log.error(msg("Failed {} failed during afterRollback PersistedEvents", callback.getClass().getName()), e);
            }
        }
    }
}
