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

public interface UnitOfWork {
    /**
     * Start the {@link UnitOfWork} and any underlying transaction
     */
    void start();

    /**
     * Commit the {@link UnitOfWork} and any underlying transaction - see {@link UnitOfWorkStatus#Committed}
     */
    void commit();

    /**
     * Roll back the {@link UnitOfWork} and any underlying transaction - see {@link UnitOfWorkStatus#RolledBack}
     *
     * @param cause the cause of the rollback
     */
    void rollback(Throwable cause);

    /**
     * Get the status of the {@link UnitOfWork}
     */
    UnitOfWorkStatus status();

    /**
     * The cause of a Rollback or a {@link #markAsRollbackOnly(Throwable)}
     */
    Throwable getCauseOfRollback();

    default void markAsRollbackOnly() {
        markAsRollbackOnly(null);
    }

    void markAsRollbackOnly(Throwable cause);

    default String info() {
        return toString();
    }

    /**
     * Roll back the {@link UnitOfWork} and any underlying transaction - see {@link UnitOfWorkStatus#RolledBack}
     */
    default void rollback() {
        // Use any exception saved using #markAsRollbackOnly(Exception)
        rollback(getCauseOfRollback());
    }

    /**
     * Register a resource (e.g. an Aggregate) that should have its {@link UnitOfWorkLifecycleCallback} called during {@link UnitOfWork} operation.<br>
     * Example:
     * <pre>{@code
     * Aggregate aggregate = unitOfWork.registerLifecycleCallbackForResource(aggregate.loadFromEvents(event),
     *                                                                       new AggregateRootRepositoryUnitOfWorkLifecycleCallback()));
     * }</pre>
     * Where the AggregateRootRepositoryUnitOfWorkLifecycleCallback is defined as:
     * <pre>{@code
     * class AggregateRootRepositoryUnitOfWorkLifecycleCallback implements UnitOfWorkLifecycleCallback<AGGREGATE_TYPE> {
     *     @Override
     *     public void beforeCommit(UnitOfWork unitOfWork, List<AGGREGATE_TYPE> associatedResources) {
     *         log.trace("beforeCommit processing {} '{}' registered with the UnitOfWork being committed", associatedResources.size(), aggregateType.getName());
     *         associatedResources.forEach(aggregate -> {
     *             log.trace("beforeCommit processing '{}' with id '{}'", aggregateType.getName(), aggregate.aggregateId());
     *             List<Object> persistableEvents = aggregate.uncommittedChanges();
     *             if (persistableEvents.isEmpty()) {
     *                 log.trace("No changes detected for '{}' with id '{}'", aggregateType.getName(), aggregate.aggregateId());
     *             } else {
     *                 if (log.isTraceEnabled()) {
     *                     log.trace("Persisting {} event(s) related to '{}' with id '{}': {}", persistableEvents.size(), aggregateType.getName(), aggregate.aggregateId(), persistableEvents.map(persistableEvent -> persistableEvent.event().getClass().getName()).reduce((s, s2) -> s + ", " + s2));
     *                 } else {
     *                     log.debug("Persisting {} event(s) related to '{}' with id '{}'", persistableEvents.size(), aggregateType.getName(), aggregate.aggregateId());
     *                 }
     *                 eventStore.persist(unitOfWork, persistableEvents);
     *                 aggregate.markChangesAsCommitted();
     *             }
     *         });
     *     }
     *
     *     @Override
     *     public void afterCommit(UnitOfWork unitOfWork, List<AGGREGATE_TYPE> associatedResources) {
     *
     *     }
     *
     *     @Override
     *     public void beforeRollback(UnitOfWork unitOfWork, List<AGGREGATE_TYPE> associatedResources, Throwable causeOfTheRollback) {
     *
     *     }
     *
     *     @Override
     *     public void afterRollback(UnitOfWork unitOfWork, List<AGGREGATE_TYPE> associatedResources, Throwable causeOfTheRollback) {
     *
     *     }
     * }
     * }
     * </pre>
     *
     * @param resource                     the resource that should be tracked
     * @param associatedUnitOfWorkCallback the callback instance for the given resource
     * @param <T>                          the type of resource
     * @return the <code>resource</code> or a proxy to it
     */
    <T> T registerLifecycleCallbackForResource(T resource, UnitOfWorkLifecycleCallback<T> associatedUnitOfWorkCallback);

    /**
     * Retrieves the list of resources (such as Aggregate instances) that are associated with a given {@link UnitOfWorkLifecycleCallback}.<br>
     * This method ensures that the provided {@link UnitOfWorkLifecycleCallback} is non-null,
     * and fetches the corresponding resources from an internal storage mechanism.
     *
     * @param associatedUnitOfWorkCallback the {@link UnitOfWorkLifecycleCallback} for which associated resources should be retrieved.
     *                                     Must not be {@code null}.
     * @param <T>                          the type of the resources managed by the provided {@link UnitOfWorkLifecycleCallback}.
     * @return an unmodifiable {@link List} of resources associated with the specified {@link UnitOfWorkLifecycleCallback},
     * or {@code empty} if no resources are associated.
     * @throws IllegalArgumentException if the provided {@link UnitOfWorkLifecycleCallback} is {@code null}.
     */
    <T> List<T> getUnitOfWorkLifecycleCallbackResources(UnitOfWorkLifecycleCallback<T> associatedUnitOfWorkCallback);

    /**
     * Retrieves every resource registered through {@link #registerLifecycleCallbackForResource(Object, UnitOfWorkLifecycleCallback)},
     * across all {@link UnitOfWorkLifecycleCallback}s.<br>
     * A registered resource is state the {@link UnitOfWork} holds in memory and acts on when it commits (e.g. an Aggregate
     * whose uncommitted events are persisted by its callback's {@code beforeCommit}), so it is not undone by rolling the
     * underlying transaction back to a savepoint. A non-empty result therefore tells that committing this
     * {@link UnitOfWork} will do more than commit the SQL written so far.
     * <p>
     * The default implementation throws {@link UnsupportedOperationException}, since a {@link UnitOfWork} that doesn't
     * track its resources can't answer; callers must treat that as "unknown" and assume resources are registered.
     * All {@link UnitOfWork} implementations provided by Essentials override it.
     *
     * @return an unmodifiable {@link List} of all registered resources, in no particular order;
     * empty if no resources are registered
     * @throws UnsupportedOperationException if this {@link UnitOfWork} doesn't expose its registered resources
     */
    default List<Object> getAllUnitOfWorkLifecycleCallbackResources() {
        throw new UnsupportedOperationException(getClass().getName() + " doesn't expose its UnitOfWorkLifecycleCallback resources");
    }
}
