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

package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.EventLog;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import org.springframework.transaction.support.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Tier 2's commit path (design phase 8 §3.4): after an append to {@code aggregateType} on an application transaction,
 * register here so that {@link EventLog#afterCommit(Collection)} runs once the transaction has committed - never before,
 * and not after a rollback (a rolled-back writer's hole retires on a later probe).
 */
public final class EventLogCommits {
    private EventLogCommits() {
    }

    /** On foundation's {@link UnitOfWork} (JDBI or Spring-managed): registers {@link EventLog#commitCallback()} */
    public static void afterCommit(UnitOfWork unitOfWork, EventLog eventLog, String aggregateType) {
        requireNonNull(unitOfWork, "No unitOfWork provided");
        requireNonNull(eventLog, "No eventLog provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        if (!unitOfWork.getUnitOfWorkLifecycleCallbackResources(eventLog.commitCallback()).contains(aggregateType)) {
            unitOfWork.registerLifecycleCallbackForResource(aggregateType, eventLog.commitCallback());
        }
    }

    /**
     * On the current Spring transaction: one {@link TransactionSynchronization} per transaction and event log calls
     * {@link EventLog#commitCallback()} with every type registered, after commit. Without an active synchronization
     * (autocommit) it wakes at once.
     */
    public static void afterSpringCommit(EventLog eventLog, String aggregateType) {
        requireNonNull(eventLog, "No eventLog provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eventLog.afterCommit(List.of(aggregateType));
            return;
        }
        var key     = new Key(eventLog);
        var pending = (Synchronization) TransactionSynchronizationManager.getResource(key);
        if (pending == null) {
            pending = new Synchronization(eventLog, key);
            TransactionSynchronizationManager.bindResource(key, pending);
            TransactionSynchronizationManager.registerSynchronization(pending);
        }
        pending.types.add(aggregateType);
    }

    private record Key(EventLog eventLog) {
    }

    private static final class Synchronization implements TransactionSynchronization {
        private final EventLog          eventLog;
        private final Key               key;
        private final Set<String>       types = new LinkedHashSet<>();

        Synchronization(EventLog eventLog, Key key) {
            this.eventLog = eventLog;
            this.key = key;
        }

        @Override
        public void afterCommit() {
            eventLog.commitCallback().afterCommit(null, List.copyOf(types));
        }

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(key);
        }
    }
}
