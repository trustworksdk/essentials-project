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
package dk.trustworks.essentials.components.eventlog;

import dk.trustworks.essentials.components.foundation.transaction.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Tier 2's glue to foundation's {@link UnitOfWork} (design phase 6 §2.2): register an appended aggregate type's name as a
 * resource with this callback ({@link UnitOfWork#registerLifecycleCallbackForResource}) and the unit of work calls
 * {@link EventLog#afterCommit(java.util.Collection)} with every registered type once it has committed. Before the commit
 * there is nothing to do, and a rollback wakes nothing: a rolled-back writer's hole retires on a later probe.
 * Package-private until phase 8's {@code EventStore} adapter registers it.
 */
final class EventLogCommitCallback implements UnitOfWorkLifecycleCallback<String> {
    private final EventLog eventLog;

    EventLogCommitCallback(EventLog eventLog) {
        this.eventLog = requireNonNull(eventLog, "No eventLog provided");
    }

    @Override
    public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<String> associatedResources) {
        return BeforeCommitProcessingStatus.COMPLETED;
    }

    @Override
    public void afterCommit(UnitOfWork unitOfWork, List<String> associatedResources) {
        eventLog.afterCommit(associatedResources);
    }

    @Override
    public void beforeRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
    }

    @Override
    public void afterRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
    }

    /**
     * A wake holds no state a rollback to a savepoint could fail to undo: it is a hint, and the appended rows roll back
     * with the transaction
     */
    @Override
    public boolean hasPendingChanges(String resource) {
        return false;
    }
}
