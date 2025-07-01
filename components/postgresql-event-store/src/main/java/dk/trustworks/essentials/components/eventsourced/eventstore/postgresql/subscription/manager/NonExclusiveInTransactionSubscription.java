/*
 *
 *  * Copyright 2021-2025 the original author or authors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.manager;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.time.StopWatch;

import java.util.Optional;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

public class NonExclusiveInTransactionSubscription implements EventStoreSubscription {
    private final EventStore                         eventStore;
    private final AggregateType                      aggregateType;
    private final SubscriberId                       subscriberId;
    private final Optional<Tenant>                   onlyIncludeEventsForTenant;
    private final TransactionalPersistedEventHandler eventHandler;

    private volatile boolean started;

    public NonExclusiveInTransactionSubscription(EventStore eventStore,
                                                 AggregateType aggregateType,
                                                 SubscriberId subscriberId,
                                                 Optional<Tenant> onlyIncludeEventsForTenant,
                                                 TransactionalPersistedEventHandler eventHandler) {
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.subscriberId = requireNonNull(subscriberId, "No subscriberId provided");
        this.onlyIncludeEventsForTenant = requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant provided");
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");

    }

    @Override
    public SubscriberId subscriberId() {
        return subscriberId;
    }

    @Override
    public AggregateType aggregateType() {
        return aggregateType;
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
                                                                                     EventStoreSubscriptionManager.DefaultEventStoreSubscriptionManager.NonExclusiveInTransactionSubscription.this,
                                                                                     handleEventTiming.stop().getDuration());

                                          if (persistedEvents.commitStage == CommitStage.Flush) {
                                              persistedEvents.unitOfWork.removeFlushedEventPersisted(event);
                                          }
                                      } catch (Throwable cause) {
                                          rethrowIfCriticalError(cause);
                                          eventStoreSubscriptionObserver.handleEventFailed(event,
                                                                                           eventHandler,
                                                                                           cause,
                                                                                           EventStoreSubscriptionManager.DefaultEventStoreSubscriptionManager.NonExclusiveInTransactionSubscription.this);
                                          onErrorHandlingEvent(event, cause);
                                      }
                                  });

        }
    }

    protected void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
        // TODO: Add better retry mechanism, poison event handling, etc.
        log.error(msg("[{}-{}] (#{}) Skipping {} event because of error",
                      subscriberId,
                      aggregateType,
                      e.globalEventOrder(),
                      e.event().getEventTypeOrName().getValue()), cause);
    }

    @Override
    public boolean isStarted() {
        return started;
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
    public void unsubscribe() {
        log.info("[{}-{}] Initiating unsubscription",
                 subscriberId,
                 aggregateType);
        eventStoreSubscriptionObserver.unsubscribing(this);
        EventStoreSubscriptionManager.DefaultEventStoreSubscriptionManager.this.unsubscribe(this);
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
    public Optional<Tenant> onlyIncludeEventsForTenant() {
        return onlyIncludeEventsForTenant;
    }

    @Override
    public boolean isActive() {
        return started;
    }

    @Override
    public String toString() {
        return "NonExclusiveInTransactionSubscription{" +
                "aggregateType=" + aggregateType +
                ", subscriberId=" + subscriberId +
                ", onlyIncludeEventsForTenant=" + onlyIncludeEventsForTenant +
                ", started=" + started +
                '}';
    }

    @Override
    public void request(long n) {
        // NOP
    }
}
