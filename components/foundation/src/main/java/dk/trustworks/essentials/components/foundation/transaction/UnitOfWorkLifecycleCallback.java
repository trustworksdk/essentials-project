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

import java.util.List;

/**
 * Callback that can be registered with a {@link UnitOfWork} in relation to
 * one of more Resources (can e.g. be an Aggregate). When the {@link UnitOfWork} is committed
 * or rolledback the {@link UnitOfWorkLifecycleCallback} will be called with all the Resources that have been associated with it through
 * the {@link UnitOfWork}
 */
public interface UnitOfWorkLifecycleCallback<RESOURCE_TYPE> {
    enum BeforeCommitProcessingStatus {
        REQUIRED,
        COMPLETED
    }

    BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<RESOURCE_TYPE> associatedResources);

    void afterCommit(UnitOfWork unitOfWork, List<RESOURCE_TYPE> associatedResources);

    void beforeRollback(UnitOfWork unitOfWork, List<RESOURCE_TYPE> associatedResources, Throwable causeOfTheRollback);

    void afterRollback(UnitOfWork unitOfWork, List<RESOURCE_TYPE> associatedResources, Throwable causeOfTheRollback);

    /**
     * Would committing the {@link UnitOfWork} make this callback persist or publish something for the given
     * <code>resource</code>? E.g. an Aggregate that only was loaded has no pending changes, whereas an Aggregate that
     * had an event applied has, since {@link #beforeCommit(UnitOfWork, List)} will persist its uncommitted events.<br>
     * Used to tell whether a {@link UnitOfWork} holds in-memory state that rolling the underlying transaction back to a
     * savepoint cannot undo - see {@link UnitOfWork#hasLifecycleCallbackResourcesWithPendingChanges()}.
     * <p>
     * The default implementation returns {@code true}, so a callback that doesn't override it is assumed to act on
     * every resource registered with it - the safe answer when it cannot be told. Override it when a registered
     * resource can be unchanged.
     *
     * @param resource a resource registered with this callback through
     *                 {@link UnitOfWork#registerLifecycleCallbackForResource(Object, UnitOfWorkLifecycleCallback)}
     * @return {@code true} if committing the {@link UnitOfWork} would persist or publish something for the <code>resource</code>,
     * {@code false} if committing leaves it untouched
     */
    default boolean hasPendingChanges(RESOURCE_TYPE resource) {
        return true;
    }
}
