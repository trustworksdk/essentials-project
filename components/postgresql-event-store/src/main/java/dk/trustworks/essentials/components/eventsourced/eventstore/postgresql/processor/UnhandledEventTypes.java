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
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
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
 *     {@link MessageHandler} methods accept - one INFO line per processor. A {@link ViewEventProcessor} logs
 *     {@link #summarizeView(Object, PatternMatchingMessageHandler)} instead, which adds the types it ignores on purpose
 *     ({@link IgnoresEventTypes}) and WARNs about the types a sealed event type permits that it neither handles nor ignores</li>
 *     <li>For every event the processor ignores, {@link #report(PersistedEvent)} notifies
 *     {@link EventStoreSubscriptionObserver#eventTypeNotHandledByProcessor(String, PersistedEvent)} - which the
 *     Micrometer observer counts when a {@code MeterRegistry} is available - and logs it: for a {@link Reporting#WARN}
 *     processor (a {@link ViewEventProcessor}) a WARN per event type, at most once per {@link #WARN_INTERVAL}, with the
 *     number of events of that type ignored since the previous WARN; for a {@link Reporting#DEBUG} processor
 *     ({@link EventProcessor}, {@link InTransactionEventProcessor}), where skipping events is normal, at DEBUG. An event
 *     type listed in the processor's {@link IgnoresEventTypes} is counted but only ever logged at DEBUG</li>
 * </ul>
 * The logging uses this class's logger name
 * ({@code dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.UnhandledEventTypes}).
 */
final class UnhandledEventTypes {
    private static final Logger   log           = LoggerFactory.getLogger(UnhandledEventTypes.class);
    /**
     * How often, at most, the WARN is repeated for one event type of one processor
     */
    static final         Duration WARN_INTERVAL = Duration.ofHours(1);

    /**
     * How an ignored event is logged
     */
    enum Reporting {
        /**
         * Rate-limited WARN per event type - for a {@link ViewEventProcessor}, where a skipped event type means the view
         * does not see every {@code EventOrder} of the aggregate
         */
        WARN,
        /**
         * DEBUG only - for {@link EventProcessor} and {@link InTransactionEventProcessor}, where skipping events is normal
         */
        DEBUG
    }

    private final String                                processorName;
    private final EventStore                            eventStore;
    private final PatternMatchingMessageHandler         messageHandler;
    private final Reporting                             reporting;
    private final DeliberatelyIgnored                   deliberatelyIgnored;
    private final ConcurrentMap<String, IgnoredEventType> ignoredEventTypes = new ConcurrentHashMap<>();
    private final Clock                                 clock;

    /**
     * @param processorName  the processor's name
     * @param eventStore     the event store, whose {@link EventStoreSubscriptionObserver} is notified of every ignored event
     * @param processor      the processor - its {@link IgnoresEventTypes} annotation, if any, is read
     * @param messageHandler the processor's {@link MessageHandler} dispatcher
     * @param reporting      how an ignored event is logged
     */
    UnhandledEventTypes(String processorName, EventStore eventStore, Object processor, PatternMatchingMessageHandler messageHandler, Reporting reporting) {
        this(processorName, eventStore, processor, messageHandler, reporting, Clock.systemUTC());
    }

    UnhandledEventTypes(String processorName, EventStore eventStore, Object processor, PatternMatchingMessageHandler messageHandler, Reporting reporting, Clock clock) {
        this.processorName = requireNonNull(processorName, "No processorName provided");
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.messageHandler = requireNonNull(messageHandler, "No messageHandler provided");
        this.reporting = requireNonNull(reporting, "No reporting provided");
        this.deliberatelyIgnored = DeliberatelyIgnored.of(requireNonNull(processor, "No processor provided"));
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
     * Log a {@link ViewEventProcessor}'s start-up summary: one INFO line with the types it handles and the ones it
     * ignores on purpose ({@link IgnoresEventTypes}), and a WARN when a sealed event type it handles part of permits
     * types it neither handles nor ignores. Never throws.
     *
     * @param processorName  the processor's name
     * @param processor      the processor
     * @param messageHandler the processor's {@link MessageHandler} dispatcher
     * @return the summary, or empty if it could not be determined
     */
    static Optional<ViewSummary> logViewSummary(String processorName, Object processor, PatternMatchingMessageHandler messageHandler) {
        try {
            var summary = summarizeView(processor, messageHandler);
            log.info("[{}] {} handles these event/message types: {}{}",
                     processorName,
                     processor.getClass().getSimpleName(),
                     summary.handledTypes(),
                     summary.deliberatelyIgnored().isEmpty() ? "" : " - and ignores these on purpose (@IgnoresEventTypes): " + summary.deliberatelyIgnored());
            if (!summary.unhandledTypes().isEmpty()) {
                log.warn("[{}] {}. {}", processorName, summary.describeUnhandledTypes(), ViewSummary.fix());
                EssentialsStartupReport.addToCurrent(EssentialsStartupReport.Finding.warn("ViewEventProcessor '" + processorName + "'",
                                                                                          summary.describeUnhandledTypes(),
                                                                                          ViewSummary.fix()));
            }
            return Optional.of(summary);
        } catch (RuntimeException | LinkageError e) {
            // A diagnostic must never fail the processor's start
            log.debug("[{}] Could not summarize the event types the view handles", processorName, e);
            return Optional.empty();
        }
    }

    /**
     * Work out which event types a {@link ViewEventProcessor} handles, ignores on purpose and - as far as it can be
     * determined without reading the event store - leaves unhandled: for every sealed supertype of a handled type, the
     * types it permits ({@link Class#getPermittedSubclasses()}, recursively) that no {@link MessageHandler} accepts and
     * {@link IgnoresEventTypes} does not list. A permitted type that is itself a supertype of a handled type (a
     * {@code non-sealed} branch the view handles part of) is not reported. Event types that share no sealed supertype
     * with a handled type cannot be found this way.
     *
     * @param processor      the processor
     * @param messageHandler the processor's {@link MessageHandler} dispatcher
     * @return the summary
     */
    static ViewSummary summarizeView(Object processor, PatternMatchingMessageHandler messageHandler) {
        var handledTypes = handledPayloadTypes(processor);
        var ignored      = DeliberatelyIgnored.of(processor);

        var sealedSupertypes = new LinkedHashSet<Class<?>>();
        handledTypes.forEach(handledType -> collectSealedTypes(handledType, sealedSupertypes, new HashSet<>()));

        var unhandled = new TreeMap<String, Class<?>>();
        var checked   = new TreeSet<String>();
        for (var sealedType : sealedSupertypes) {
            checked.add(sealedType.getName());
            var permitted = new LinkedHashSet<Class<?>>();
            collectPermittedLeaves(sealedType, permitted, new HashSet<>());
            for (var candidate : permitted) {
                if (messageHandler.handlesMessageWithPayload(candidate) ||
                        ignored.coversType(candidate) ||
                        handledTypes.stream().anyMatch(candidate::isAssignableFrom)) {
                    continue;
                }
                unhandled.put(candidate.getName(), candidate);
            }
        }
        return new ViewSummary(handledTypes.stream().map(Class::getSimpleName).distinct().sorted().toList(),
                               ignored.describe(),
                               List.copyOf(checked),
                               List.copyOf(unhandled.values()));
    }

    /**
     * @param processor the object declaring the {@link MessageHandler} annotated methods
     * @return the simple names of the payload types its {@link MessageHandler} methods accept, sorted
     */
    static List<String> handledMessageTypes(Object processor) {
        return handledPayloadTypes(processor).stream()
                                             .map(Class::getSimpleName)
                                             .distinct()
                                             .sorted()
                                             .toList();
    }

    private static List<Class<?>> handledPayloadTypes(Object processor) {
        return Methods.methods(processor.getClass())
                      .stream()
                      .filter(method -> method.isAnnotationPresent(MessageHandler.class))
                      .filter(method -> method.getParameterCount() >= 1 && method.getParameterCount() <= 2)
                      .<Class<?>>map(method -> method.getParameterTypes()[0])
                      .distinct()
                      .toList();
    }

    /**
     * Collect <code>type</code> itself, if sealed, and every sealed supertype (class or interface) of it
     */
    private static void collectSealedTypes(Class<?> type, Set<Class<?>> sealedTypes, Set<Class<?>> visited) {
        if (type == null || type == Object.class || !visited.add(type)) {
            return;
        }
        if (type.isSealed()) {
            sealedTypes.add(type);
        }
        collectSealedTypes(type.getSuperclass(), sealedTypes, visited);
        for (var superInterface : type.getInterfaces()) {
            collectSealedTypes(superInterface, sealedTypes, visited);
        }
    }

    /**
     * Collect the types a sealed type permits, recursively, down to the ones that are not sealed themselves
     */
    private static void collectPermittedLeaves(Class<?> sealedType, Set<Class<?>> leaves, Set<Class<?>> visited) {
        if (!visited.add(sealedType)) {
            return;
        }
        var permitted = sealedType.getPermittedSubclasses();
        if (permitted == null) {
            return;
        }
        for (var subtype : permitted) {
            if (subtype.isSealed()) {
                collectPermittedLeaves(subtype, leaves, visited);
            } else {
                leaves.add(subtype);
            }
        }
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
            if (reporting == Reporting.DEBUG || deliberatelyIgnored.covers(event)) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Ignored a '{}' event of AggregateType '{}' (global-event-order {}) - {}",
                              processorName,
                              eventType,
                              event.aggregateType(),
                              event.globalEventOrder(),
                              reporting == Reporting.DEBUG ? "none of the processor's @MessageHandler methods accepts this type" : "listed in the processor's @IgnoresEventTypes");
                }
                return;
            }
            var ignored                 = ignoredEventTypes.computeIfAbsent(eventType, type -> new IgnoredEventType());
            var ignoredSinceLastWarning = ignored.ignoredOneMore(clock.instant());
            if (ignoredSinceLastWarning > 0) {
                log.warn("[{}] Ignored {} '{}' event(s) of AggregateType '{}' (latest: global-event-order {}) - none of the view's @MessageHandler methods accepts this type, " +
                                 "so the view does not see every EventOrder of the aggregate (a projection checking that the order is contiguous breaks, and the view is incomplete). " +
                                 "Add a @MessageHandler if the view should react to it; if it deliberately ignores the type, list it in @{} on the view. " +
                                 "This WARN repeats at most every {}",
                         processorName,
                         ignoredSinceLastWarning,
                         eventType,
                         event.aggregateType(),
                         event.globalEventOrder(),
                         IgnoresEventTypes.class.getSimpleName(),
                         WARN_INTERVAL);
            }
        } catch (RuntimeException e) {
            log.debug("[{}] Could not log an ignored event", processorName, e);
        }
    }

    /**
     * A {@link ViewEventProcessor}'s start-up summary - see {@link #summarizeView(Object, PatternMatchingMessageHandler)}
     *
     * @param handledTypes        the simple names of the payload types its {@link MessageHandler} methods accept, sorted
     * @param deliberatelyIgnored the types and event names its {@link IgnoresEventTypes} lists
     * @param sealedTypesChecked  the sealed supertypes of the handled types whose permitted types were checked, by name
     * @param unhandledTypes      the types those sealed types permit that it neither handles nor ignores, sorted by name
     */
    record ViewSummary(List<String> handledTypes, List<String> deliberatelyIgnored, List<String> sealedTypesChecked, List<Class<?>> unhandledTypes) {
        /**
         * @return the sentence describing {@link #unhandledTypes()}
         */
        String describeUnhandledTypes() {
            return "The sealed event type(s) " + sealedTypesChecked + " permit " + unhandledTypes.size() + " type(s) that none of the view's @MessageHandler methods accepts: " +
                    unhandledTypes.stream().map(Class::getName).toList() + ". The view skips these events, so it does not see every EventOrder of the aggregate " +
                    "(a projection checking that the order is contiguous breaks, and the view is incomplete)";
        }

        /**
         * @return how to resolve {@link #unhandledTypes()}
         */
        static String fix() {
            return "Add a @MessageHandler for each type the view should react to, and list the ones it deliberately ignores in @" + IgnoresEventTypes.class.getSimpleName() +
                    " on the view class (a listed supertype covers its subtypes)";
        }
    }

    /**
     * The event types and names a processor's {@link IgnoresEventTypes} lists
     */
    private record DeliberatelyIgnored(List<Class<?>> types, Set<String> eventNames) {
        static DeliberatelyIgnored of(Object processor) {
            var annotation = processor.getClass().getAnnotation(IgnoresEventTypes.class);
            if (annotation == null) {
                return new DeliberatelyIgnored(List.of(), Set.of());
            }
            return new DeliberatelyIgnored(List.of(annotation.value()), Set.of(annotation.eventNames()));
        }

        boolean coversType(Class<?> type) {
            return types.stream().anyMatch(ignored -> ignored.isAssignableFrom(type));
        }

        boolean covers(PersistedEvent event) {
            if (types.isEmpty() && eventNames.isEmpty()) {
                return false;
            }
            var eventJSON = event.event();
            var eventName = eventJSON.getEventName();
            if (eventName.isPresent()) {
                return eventNames.contains(eventName.get().toString());
            }
            if (types.isEmpty()) {
                return false;
            }
            try {
                return eventJSON.getEventTypeAsJavaClass().map(this::coversType).orElse(false);
            } catch (RuntimeException e) {
                // The Java type can no longer be loaded - it cannot be a subtype of a listed one
                return false;
            }
        }

        List<String> describe() {
            var described = new ArrayList<String>();
            types.forEach(type -> described.add(type.getSimpleName()));
            eventNames.stream().sorted().forEach(name -> described.add("'" + name + "'"));
            return described;
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
