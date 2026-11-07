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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.transaction.NoActiveUnitOfWorkException;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkLifecycleCallback;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkLifecycleCallback.BeforeCommitProcessingStatus;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkStatus;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.shared.functional.CheckedConsumer;
import dk.trustworks.essentials.shared.functional.CheckedFunction;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test helper that returns a {@link HandleAwareUnitOfWorkFactory} stub which runs the
 * caller-supplied lambda inline against a mock {@link HandleAwareUnitOfWork}. Used by tests
 * that exercise framework code wrapping repository calls in {@code withUnitOfWork(...)}.
 */
final class InlineUnitOfWorkFactories {
    private InlineUnitOfWorkFactories() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> inline() {
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> factory = mock(HandleAwareUnitOfWorkFactory.class);
        var uow = mock(HandleAwareUnitOfWork.class);
        try {
            when(factory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(invocation -> {
                CheckedFunction<HandleAwareUnitOfWork, ?> function = invocation.getArgument(0);
                return function.apply(uow);
            });
            doAnswer(invocation -> {
                CheckedConsumer<HandleAwareUnitOfWork> consumer = invocation.getArgument(0);
                consumer.accept(uow);
                return null;
            }).when(factory).usingUnitOfWork(any(CheckedConsumer.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return factory;
    }

    /**
     * Like {@link #inline()}, but each outermost {@code withUnitOfWork}/{@code usingUnitOfWork} gets its own mock
     * {@link EventStoreUnitOfWork}, a nested call joins it, and {@code getCurrentUnitOfWork()}/{@code getRequiredUnitOfWork()}
     * answer the one in progress on the calling thread - as the real factories do. Lets a test tell which unit of work a
     * call ran in.
     * <p>
     * As in {@code UnitOfWorkFactory.withUnitOfWork}, a nested {@code withUnitOfWork} that throws marks the joined unit
     * of work rollback-only - its {@code status()} turns {@link UnitOfWorkStatus#MarkedForRollbackOnly} and
     * {@code getCauseOfRollback()} answers the exception - and rethrows it wrapped in a {@link UnitOfWorkException}.
     * <p>
     * Resources registered with {@code registerLifecycleCallbackForResource} are completed when the outermost call
     * returns, as {@code GenericHandleAwareUnitOfWork.commit()} does: a unit of work still
     * {@link UnitOfWorkStatus#Started} runs every callback's {@code beforeCommit} until none answers
     * {@link BeforeCommitProcessingStatus#REQUIRED}, then {@code afterCommit}; one marked rollback-only, or whose action
     * threw, runs {@code beforeRollback}/{@code afterRollback} instead and never {@code beforeCommit}. So a real
     * {@code StatefulAggregateRepository} over {@link #asEventStoreUnitOfWorkFactory(HandleAwareUnitOfWorkFactory)}
     * appends what its aggregates recorded only when the unit of work commits.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> joining() {
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> factory = mock(EventStoreUnitOfWorkFactory.class);
        var current = new ThreadLocal<TrackedUnitOfWork>();
        try {
            when(factory.getCurrentUnitOfWork()).thenAnswer(invocation -> Optional.ofNullable(current.get()).map(TrackedUnitOfWork::unitOfWork));
            when(factory.getRequiredUnitOfWork()).thenAnswer(invocation -> {
                if (current.get() == null) {
                    throw new NoActiveUnitOfWorkException();
                }
                return current.get().unitOfWork();
            });
            when(factory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(invocation -> {
                CheckedFunction<HandleAwareUnitOfWork, ?> function = invocation.getArgument(0);
                if (current.get() != null) {
                    var joined = current.get().unitOfWork();
                    try {
                        return function.apply(joined);
                    } catch (Exception e) {
                        joined.markAsRollbackOnly(e);
                        throw new UnitOfWorkException(e);
                    }
                }
                var started = trackingRollbackOnly(mock(EventStoreUnitOfWork.class));
                current.set(started);
                try {
                    Object result;
                    try {
                        result = function.apply(started.unitOfWork());
                    } catch (Exception e) {
                        started.rollback(e);
                        throw e;
                    }
                    started.commitOrRollback();
                    return result;
                } finally {
                    current.remove();
                }
            });
            doAnswer(invocation -> {
                CheckedConsumer<HandleAwareUnitOfWork> consumer = invocation.getArgument(0);
                if (current.get() != null) {
                    var joined = current.get().unitOfWork();
                    try {
                        consumer.accept(joined);
                    } catch (Exception e) {
                        joined.markAsRollbackOnly(e);
                        throw new UnitOfWorkException(e);
                    }
                    return null;
                }
                var started = trackingRollbackOnly(mock(EventStoreUnitOfWork.class));
                current.set(started);
                try {
                    try {
                        consumer.accept(started.unitOfWork());
                    } catch (Exception e) {
                        started.rollback(e);
                        throw e;
                    }
                    started.commitOrRollback();
                } finally {
                    current.remove();
                }
                return null;
            }).when(factory).usingUnitOfWork(any(CheckedConsumer.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return factory;
    }

    /**
     * The {@link #joining()} factory as the {@link EventStoreUnitOfWorkFactory} an {@code EventStore} answers, so a real
     * {@code StatefulAggregateRepository} over a mock {@code EventStore} registers its aggregates with it
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> asEventStoreUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> joiningFactory) {
        return (EventStoreUnitOfWorkFactory) joiningFactory;
    }

    /**
     * Make the mock {@code unitOfWork} answer {@link UnitOfWorkStatus#Started} until {@code markAsRollbackOnly} is
     * called, then {@link UnitOfWorkStatus#MarkedForRollbackOnly} with that call's cause, and keep the resources
     * registered with it for {@link TrackedUnitOfWork#commitOrRollback()}.
     */
    @SuppressWarnings("unchecked")
    private static TrackedUnitOfWork trackingRollbackOnly(EventStoreUnitOfWork unitOfWork) {
        var tracked = new TrackedUnitOfWork(unitOfWork, new AtomicReference<>(UnitOfWorkStatus.Started), new AtomicReference<>(), new LinkedHashMap<>());
        when(unitOfWork.status()).thenAnswer(invocation -> tracked.status().get());
        when(unitOfWork.getCauseOfRollback()).thenAnswer(invocation -> tracked.cause().get());
        doAnswer(invocation -> {
            tracked.cause().set(invocation.getArgument(0));
            tracked.status().set(UnitOfWorkStatus.MarkedForRollbackOnly);
            return null;
        }).when(unitOfWork).markAsRollbackOnly(any());
        when(unitOfWork.registerLifecycleCallbackForResource(any(), any())).thenAnswer(invocation -> {
            Object                              resource = invocation.getArgument(0);
            UnitOfWorkLifecycleCallback<Object> callback = invocation.getArgument(1);
            tracked.registrations().computeIfAbsent(callback, ignored -> new ArrayList<>()).add(resource);
            return resource;
        });
        when(unitOfWork.getUnitOfWorkLifecycleCallbackResources(any())).thenAnswer(invocation -> List.copyOf(tracked.registrations().getOrDefault(invocation.getArgument(0), List.of())));
        return tracked;
    }

    /**
     * A mock unit of work from {@link #joining()} with the status and lifecycle-callback resources it tracks
     */
    private record TrackedUnitOfWork(EventStoreUnitOfWork unitOfWork,
                                     AtomicReference<UnitOfWorkStatus> status,
                                     AtomicReference<Throwable> cause,
                                     Map<UnitOfWorkLifecycleCallback<Object>, List<Object>> registrations) {
        /**
         * Commit when still {@link UnitOfWorkStatus#Started}, roll back when marked rollback-only - as
         * {@code GenericHandleAwareUnitOfWork.commit()} does
         */
        void commitOrRollback() {
            if (status.get() != UnitOfWorkStatus.Started) {
                rollback(cause.get());
                return;
            }
            var processingStatus = BeforeCommitProcessingStatus.REQUIRED;
            var passes           = 0;
            while (processingStatus == BeforeCommitProcessingStatus.REQUIRED) {
                passes++;
                if (passes > 10) {
                    throw new IllegalStateException("beforeCommit still answers REQUIRED after 10 passes");
                }
                processingStatus = BeforeCommitProcessingStatus.COMPLETED;
                for (var registration : new LinkedHashMap<>(registrations).entrySet()) {
                    if (registration.getKey().beforeCommit(unitOfWork, List.copyOf(registration.getValue())) == BeforeCommitProcessingStatus.REQUIRED) {
                        processingStatus = BeforeCommitProcessingStatus.REQUIRED;
                    }
                }
            }
            status.set(UnitOfWorkStatus.Committed);
            registrations.forEach((callback, resources) -> callback.afterCommit(unitOfWork, List.copyOf(resources)));
        }

        void rollback(Throwable causeOfRollback) {
            registrations.forEach((callback, resources) -> callback.beforeRollback(unitOfWork, List.copyOf(resources), causeOfRollback));
            status.set(UnitOfWorkStatus.RolledBack);
            registrations.forEach((callback, resources) -> callback.afterRollback(unitOfWork, List.copyOf(resources), causeOfRollback));
        }
    }

    /**
     * Like {@link #inline()}, but every exception thrown inside {@code withUnitOfWork}/{@code usingUnitOfWork} reaches
     * the caller wrapped in a {@link UnitOfWorkException}, whether the call is outermost or nested - as
     * {@code UnitOfWorkFactory.withUnitOfWork} does. {@link #inline()} passes exceptions through unwrapped, so a test
     * that asserts which exception a caller gets from a real factory uses this one.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> wrapping() {
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> factory = mock(HandleAwareUnitOfWorkFactory.class);
        var uow = mock(HandleAwareUnitOfWork.class);
        try {
            when(factory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(invocation -> {
                CheckedFunction<HandleAwareUnitOfWork, ?> function = invocation.getArgument(0);
                try {
                    return function.apply(uow);
                } catch (Exception e) {
                    throw new UnitOfWorkException(e);
                }
            });
            doAnswer(invocation -> {
                CheckedConsumer<HandleAwareUnitOfWork> consumer = invocation.getArgument(0);
                try {
                    consumer.accept(uow);
                } catch (Exception e) {
                    throw new UnitOfWorkException(e);
                }
                return null;
            }).when(factory).usingUnitOfWork(any(CheckedConsumer.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return factory;
    }

    /**
     * An in-memory generation repository whose {@code withGenerationLock} runs inside
     * {@code unitOfWorkFactory.withUnitOfWork}, as {@code PostgresqlClosingBooksGenerationRepository}'s does. With
     * {@link #wrapping()}, an exception thrown under the lock reaches the caller wrapped by the lock too.
     */
    static <ID> InMemoryClosingBooksGenerationResolver<ID> generationLockInUnitOfWork(HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> unitOfWorkFactory) {
        return new InMemoryClosingBooksGenerationResolver<>() {
            @Override
            public <R> R withGenerationLock(AggregateType aggregateType,
                                            LogicalAggregateId<ID> logicalAggregateId,
                                            Supplier<R> rollover) {
                return unitOfWorkFactory.withUnitOfWork(uow -> super.withGenerationLock(aggregateType, logicalAggregateId, rollover));
            }
        };
    }
}
