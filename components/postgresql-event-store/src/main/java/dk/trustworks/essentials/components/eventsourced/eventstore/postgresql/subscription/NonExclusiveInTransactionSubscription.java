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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.CommitStage;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.PersistedEvents;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.components.foundation.types.Tenant;
import dk.trustworks.essentials.shared.Exceptions;
import dk.trustworks.essentials.shared.time.StopWatch;

import java.util.Optional;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Represents a non-exclusive subscription to events for a specific aggregate type from an event store.
 * <p>
 * The subscription processes events transactionally in the context of the commit stages
 * (`BeforeCommit` and `Flush`) and uses a provided {@link TransactionalPersistedEventHandler}
 * to handle persisted events. This subscription is not exclusive and operates in-transaction,
 * meaning that it cannot reset or provide a resume point for the subscription.
 */
public class NonExclusiveInTransactionSubscription extends AbstractEventStoreSubscription {
    private final TransactionalPersistedEventHandler eventHandler;

    public NonExclusiveInTransactionSubscription(EventStore eventStore,
                                                 AggregateType aggregateType,
                                                 SubscriberId subscriberId,
                                                 Optional<Tenant> onlyIncludeEventsForTenant,
                                                 TransactionalPersistedEventHandler eventHandler,
                                                 EventStoreSubscriptionObserver eventStoreSubscriptionObserver,
                                                 Consumer<EventStoreSubscription> unsubscribeCallback) {
        super(eventStore, aggregateType, subscriberId, onlyIncludeEventsForTenant, eventStoreSubscriptionObserver, unsubscribeCallback);
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");
    }

    @Override
    public void start() {
        if (!started) {
            started = true;

            log.info("[{}-{}] Starting subscription",
                    subscriberId,
                    aggregateType);

            eventStore.localEventBus()
                    .addSyncSubscriber(this::onEvent);

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
                                    NonExclusiveInTransactionSubscription.this,
                                    handleEventTiming.stop().getDuration());

                            if (persistedEvents.commitStage == CommitStage.Flush) {
                                persistedEvents.unitOfWork.removeFlushedEventPersisted(event);
                            }
                        } catch (Throwable cause) {
                            rethrowIfCriticalError(cause);
                            eventStoreSubscriptionObserver.handleEventFailed(event,
                                    eventHandler,
                                    cause,
                                    NonExclusiveInTransactionSubscription.this);
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
                        .removeSyncSubscriber(this::onEvent);
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
        return false;
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
        return started;
    }

    @Override
    public void request(long n) {
        // NOP
    }
}
