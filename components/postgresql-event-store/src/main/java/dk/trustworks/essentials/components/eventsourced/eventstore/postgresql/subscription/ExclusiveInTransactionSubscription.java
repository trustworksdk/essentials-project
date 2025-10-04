/*
 * Copyright 2021-2025 the original author or authors.
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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.Exceptions;
import dk.trustworks.essentials.shared.time.StopWatch;

import java.util.Optional;
import java.util.function.*;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Represents an exclusive and transactional subscription for the event store.
 * The subscription ensures that only one instance is actively consuming events
 * for a specific {@link AggregateType} and {@link SubscriberId} at any given time.
 * It leverages a distributed lock mechanism to enforce exclusivity.
 * Events are processed within the boundaries of a transaction.
 */
public class ExclusiveInTransactionSubscription extends AbstractEventStoreSubscription implements ExclusiveSubscription{
    private final FencedLockManager fencedLockManager;
    private final TransactionalPersistedEventHandler eventHandler;
    private final LockName lockName;

    private volatile boolean active;

    public ExclusiveInTransactionSubscription(EventStore eventStore,
                                              FencedLockManager fencedLockManager,
                                              AggregateType aggregateType,
                                              SubscriberId subscriberId,
                                              Optional<Tenant> onlyIncludeEventsForTenant,
                                              TransactionalPersistedEventHandler eventHandler,
                                              EventStoreSubscriptionObserver eventStoreSubscriptionObserver,
                                              Consumer<EventStoreSubscription> unsubscribeCallback,
                                              Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory) {
        super(eventStore, aggregateType, subscriberId, onlyIncludeEventsForTenant, eventStoreSubscriptionObserver, unsubscribeCallback, eventStorePollingOptimizerFactory);
        this.fencedLockManager = requireNonNull(fencedLockManager, "No fencedLockManager provided");
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");
        this.lockName = LockName.of(msg("[{}-{}]", subscriberId, aggregateType));
    }

    @Override
    public void start() {
        if (!started) {
            started = true;

            log.info("[{}-{}] Starting subscription",
                    subscriberId,
                    aggregateType);

            fencedLockManager.acquireLockAsync(lockName, new LockCallback() {
                @Override
                public void lockAcquired(FencedLock lock) {
                    log.info("[{}-{}] Acquired lock.",
                            subscriberId,
                            aggregateType);
                    active = true;
                    eventStoreSubscriptionObserver.lockAcquired(lock, ExclusiveInTransactionSubscription.this);

                    eventStore.localEventBus()
                            .addSyncSubscriber(ExclusiveInTransactionSubscription.this::onEvent);

                }

                @Override
                public void lockReleased(FencedLock lock) {
                    if (!active) {
                        return;
                    }
                    log.info("[{}-{}] Lock Released. Stopping subscription",
                            subscriberId,
                            aggregateType);
                    eventStoreSubscriptionObserver.lockReleased(lock, ExclusiveInTransactionSubscription.this);
                    try {
                        eventStore.localEventBus()
                                .removeSyncSubscriber(ExclusiveInTransactionSubscription.this::onEvent);
                    } catch (Exception e) {
                        log.error(msg("[{}-{}] Failed to dispose subscription flux",
                                subscriberId,
                                aggregateType), e);
                    }

                    active = false;
                    log.info("[{}-{}] Stopped subscription",
                            subscriberId,
                            aggregateType);
                }

            });

        } else {
            log.debug("[{}-{}] Subscription was already started",
                    subscriberId,
                    aggregateType);
        }
    }

    private void onEvent(Object e) {
        if (!(e instanceof PersistedEvents)) {
            return;
        }

        var persistedEvents = (PersistedEvents) e;
        if (persistedEvents.commitStage == CommitStage.BeforeCommit || persistedEvents.commitStage == CommitStage.Flush) {
            persistedEvents.events.stream()
                    .filter(event -> event.aggregateType().equals(aggregateType))
                    .forEach(event -> {
                        log.trace("[{}-{}] (#{}) Received {} event with eventId '{}', aggregateId: '{}', eventOrder: {} during commit-stage '{}'",
                                subscriberId,
                                aggregateType,
                                event.globalEventOrder(),
                                event.event().getEventTypeOrName().toString(),
                                event.eventId(),
                                event.aggregateId(),
                                event.eventOrder(),
                                persistedEvents.commitStage
                        );
                        try {
                            var handleEventTiming = StopWatch.start("handleEvent (" + subscriberId + ", " + aggregateType + ")");
                            eventHandler.handle(event, persistedEvents.unitOfWork);
                            eventStoreSubscriptionObserver.handleEvent(event,
                                    eventHandler,
                                    ExclusiveInTransactionSubscription.this,
                                    handleEventTiming.stop().getDuration());
                            if (persistedEvents.commitStage == CommitStage.Flush) {
                                persistedEvents.unitOfWork.removeFlushedEventPersisted(event);
                            }
                        } catch (Throwable cause) {
                            rethrowIfCriticalError(cause);
                            eventStoreSubscriptionObserver.handleEventFailed(event,
                                    eventHandler,
                                    cause,
                                    ExclusiveInTransactionSubscription.this);
                            onErrorHandlingEvent(event, cause);
                        }
                    });

        }
    }

    @Override
    protected void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
        // TODO: Add better retry mechanism, poison event handling, etc.
        super.onErrorHandlingEvent(e, cause);
        Exceptions.sneakyThrow(cause);
    }

    @Override
    public void stop() {
        if (started) {
            log.info("[{}-{}] Stopping subscription",
                    subscriberId,
                    aggregateType);
            try {
                log.debug("[{}-{}] Stopping subscription flux",
                        subscriberId,
                        aggregateType);
                eventStore.localEventBus()
                        .removeSyncSubscriber(ExclusiveInTransactionSubscription.this::onEvent);
            } catch (Exception e) {
                log.error(msg("[{}-{}] Failed to dispose subscription flux",
                        subscriberId,
                        aggregateType), e);
            }
            started = false;
            log.info("[{}-{}] Stopped subscription",
                    subscriberId,
                    aggregateType);
        }
    }

    @Override
    public boolean isExclusive() {
        return true;
    }

    @Override
    public boolean isInTransaction() {
        return true;
    }

    @Override
    public void resetFrom(GlobalEventOrder subscribeFromAndIncludingGlobalOrder, Consumer<GlobalEventOrder> resetProcessor) {
        throw new EventStoreException(msg("[{}-{}] Reset of ResumePoint isn't support for an In-Transaction subscription",
                subscriberId,
                aggregateType));
    }

    @Override
    public Optional<SubscriptionResumePoint> currentResumePoint() {
        return Optional.empty();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void request(long n) {
        // NOP
    }

    @Override
    public LockName lockName() {
        return lockName;
    }
}
