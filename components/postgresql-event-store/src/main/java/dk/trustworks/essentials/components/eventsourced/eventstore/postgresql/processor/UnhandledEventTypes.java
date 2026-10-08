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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.PatternMatchingMessageHandler;
import dk.trustworks.essentials.shared.reflection.Methods;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Makes the event types an event processor has no {@link MessageHandler} for visible (CB-4). Event processors allow
 * unmatched events, so an event type without a handler used to be dropped without any trace.
 * <ul>
 *     <li>At start, {@link #logHandledMessageTypes(String, Object)} logs the payload types the processor's
 *     {@link MessageHandler} methods accept - one INFO line per processor</li>
 *     <li>For every event the processor ignores, {@link #report(PersistedEvent)} notifies
 *     {@link EventStoreSubscriptionObserver#eventTypeNotHandledByProcessor(String, PersistedEvent)} - which the
 *     Micrometer observer counts when a {@code MeterRegistry} is available - and logs a WARN per event type, at most
 *     once per {@link #WARN_INTERVAL}, with the number of events of that type ignored since the previous WARN</li>
 * </ul>
 * The WARN is logged under this class's logger name
 * ({@code dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.UnhandledEventTypes}), so
 * an application whose processors deliberately ignore event types can turn it down in its logging configuration.
 */
final class UnhandledEventTypes {
    private static final Logger   log           = LoggerFactory.getLogger(UnhandledEventTypes.class);
    /**
     * How often, at most, the WARN is repeated for one event type of one processor
     */
    static final         Duration WARN_INTERVAL = Duration.ofHours(1);

    private final String                                processorName;
    private final EventStore                            eventStore;
    private final PatternMatchingMessageHandler         messageHandler;
    private final ConcurrentMap<String, IgnoredEventType> ignoredEventTypes = new ConcurrentHashMap<>();
    private final Clock                                 clock;

    UnhandledEventTypes(String processorName, EventStore eventStore, PatternMatchingMessageHandler messageHandler) {
        this(processorName, eventStore, messageHandler, Clock.systemUTC());
    }

    UnhandledEventTypes(String processorName, EventStore eventStore, PatternMatchingMessageHandler messageHandler, Clock clock) {
        this.processorName = requireNonNull(processorName, "No processorName provided");
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.messageHandler = requireNonNull(messageHandler, "No messageHandler provided");
        this.clock = requireNonNull(clock, "No clock provided");
    }

    /**
     * Log, at INFO, the payload types the {@link MessageHandler} annotated methods of the processor accept
     *
     * @param processorName the processor's name
     * @param processor     the object declaring the {@link MessageHandler} annotated methods
     */
    static void logHandledMessageTypes(String processorName, Object processor) {
        try {
            log.info("[{}] {} handles these event/message types: {}", processorName, processor.getClass().getSimpleName(), handledMessageTypes(processor));
        } catch (RuntimeException e) {
            // A diagnostic must never fail the processor's start
            log.debug("[{}] Could not resolve the message types the processor handles", processorName, e);
        }
    }

    /**
     * @param processor the object declaring the {@link MessageHandler} annotated methods
     * @return the simple names of the payload types its {@link MessageHandler} methods accept, sorted
     */
    static List<String> handledMessageTypes(Object processor) {
        return Methods.methods(processor.getClass())
                      .stream()
                      .filter(method -> method.isAnnotationPresent(MessageHandler.class))
                      .filter(method -> method.getParameterCount() >= 1 && method.getParameterCount() <= 2)
                      .map(method -> method.getParameterTypes()[0].getSimpleName())
                      .distinct()
                      .sorted()
                      .toList();
    }

    /**
     * @param event an event delivered to the processor
     * @return true if one of the processor's {@link MessageHandler} methods accepts the event's type; an event persisted
     * with an event name and no Java type is never handled
     */
    boolean isHandled(PersistedEvent event) {
        var javaType = event.event().getEventTypeAsJavaClass();
        return javaType.isPresent() && messageHandler.handlesMessageWithPayload(javaType.get());
    }

    /**
     * Report the event if the processor has no {@link MessageHandler} for its type
     *
     * @param event an event delivered to the processor
     * @return true if the event is handled by the processor, false if it was reported as ignored
     */
    boolean reportIfUnhandled(PersistedEvent event) {
        try {
            if (isHandled(event)) {
                return true;
            }
        } catch (RuntimeException e) {
            // E.g. the event's Java type can no longer be loaded - the handling path reports that itself
            log.debug("[{}] Could not resolve whether the processor handles event '{}'", processorName, event.eventId(), e);
            return true;
        }
        report(event);
        return false;
    }

    /**
     * Report an event the processor ignored because none of its {@link MessageHandler} methods accepts its type.
     * Never throws.
     *
     * @param event the ignored event
     */
    void report(PersistedEvent event) {
        try {
            eventStore.getEventStoreSubscriptionObserver().eventTypeNotHandledByProcessor(processorName, event);
        } catch (RuntimeException e) {
            log.debug("[{}] EventStoreSubscriptionObserver failed to record an ignored event", processorName, e);
        }
        try {
            var eventType = event.event().getEventTypeOrNamePersistenceValue();
            var ignored   = ignoredEventTypes.computeIfAbsent(eventType, type -> new IgnoredEventType());
            var ignoredSinceLastWarning = ignored.ignoredOneMore(clock.instant());
            if (ignoredSinceLastWarning > 0) {
                log.warn("[{}] Ignored {} '{}' event(s) of AggregateType '{}' (latest: global-event-order {}) - none of the processor's @MessageHandler methods accepts this type. " +
                                 "Add a @MessageHandler if the processor should react to it; if it deliberately ignores the type, this WARN repeats at most every {} " +
                                 "(logger '{}')",
                         processorName,
                         ignoredSinceLastWarning,
                         eventType,
                         event.aggregateType(),
                         event.globalEventOrder(),
                         WARN_INTERVAL,
                         UnhandledEventTypes.class.getName());
            }
        } catch (RuntimeException e) {
            log.debug("[{}] Could not log an ignored event", processorName, e);
        }
    }

    /**
     * Rate limits the WARN for one event type
     */
    private static final class IgnoredEventType {
        private long    ignoredSinceLastWarning;
        private Instant lastWarning;

        /**
         * @return the number of events of this type ignored since the last WARN, if a WARN is due now; otherwise 0
         */
        synchronized long ignoredOneMore(Instant now) {
            ignoredSinceLastWarning++;
            if (lastWarning == null || !now.isBefore(lastWarning.plus(WARN_INTERVAL))) {
                lastWarning = now;
                var ignored = ignoredSinceLastWarning;
                ignoredSinceLastWarning = 0;
                return ignored;
            }
            return 0;
        }
    }
}
