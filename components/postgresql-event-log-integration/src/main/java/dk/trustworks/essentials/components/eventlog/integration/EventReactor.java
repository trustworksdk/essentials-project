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

package dk.trustworks.essentials.components.eventlog.integration;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import org.slf4j.*;

import java.sql.SQLException;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Reacts to events with effects outside the database - commands, queues, HTTP (design phase 8 §3.3): a {@code FRESH},
 * exclusive, periodic subscription, delivering each event at least once. Failures, retries and parking are the engine's;
 * the reactor catches nothing (I14).
 *
 * <pre>{@code
 * var reactor = EventReactor.builder("order-notifier", "Orders")
 *         .on(OrderPlaced.class, event -> notifier.send(event.payloadAs(OrderPlaced.class)))
 *         .build(eventLog, serializer);
 * reactor.start();
 * }</pre>
 */
public final class EventReactor {
    private static final Logger log = LoggerFactory.getLogger(EventReactor.class);

    private final EventLog                 eventLog;
    private final SubscriptionDefinition   definition;
    private final EventDeserializer        deserializer;
    private final Dispatch<Reaction<?>>    dispatch;
    private final ProcessorSeams           seams;
    private final LifecycleGate            gate = new LifecycleGate();
    private volatile SubscriptionHandle    handle;

    private EventReactor(EventLog eventLog, SubscriptionDefinition definition, EventDeserializer deserializer, Dispatch<Reaction<?>> dispatch, ProcessorSeams seams) {
        this.eventLog = eventLog;
        this.definition = definition;
        this.deserializer = deserializer;
        this.dispatch = dispatch;
        this.seams = seams;
    }

    public static Builder builder(String subscriber, String aggregateType) {
        return new Builder(subscriber, aggregateType);
    }

    /**
     * Start delivering (an exclusive reactor may start as a standby). Idempotent while running; waits for a stop, reset
     * or rebuild in progress (see {@link LifecycleGate})
     */
    public EventReactor start() throws SQLException {
        try (var ignored = gate.enter()) {
            subscribeIfNotRunning();
        }
        return this;
    }

    /** Stop delivering: the resume point is saved once more and an exclusive reactor releases its lease. Idempotent */
    public void stop() {
        try (var ignored = gate.enter()) {
            stopSubscription();
        }
    }

    private void subscribeIfNotRunning() throws SQLException {
        if (!isRunning()) {
            handle = seams.subscriber().subscribe(eventLog, definition, (PeriodicHandler) this::handle, null);
        }
    }

    private void stopSubscription() {
        var current = handle;
        if (current != null) {
            current.stop();
        }
    }

    public boolean isRunning() {
        var current = handle;
        return current != null && current.isRunning();
    }

    public SubscriptionDefinition definition() {
        return definition;
    }

    public Optional<SubscriptionHandle> handle() {
        return Optional.ofNullable(handle);
    }

    public ProgressKey key() {
        return ProgressKey.of(definition);
    }

    /**
     * Move the reactor to {@code from} (§3.3): stop, reset (epoch + 1, parked rows deleted), start again if it ran.
     * {@link StartFrom#head()} is the aggregate type's head at the time of the call.
     * <p>
     * Runs as one {@link LifecycleGate} transition, so a concurrent {@link #start()} cannot resume the reactor between the
     * stop and the reset; no monitor is held while the stop joins the delivery thread.
     *
     * @return {@code false} if the subscription has no progress row yet
     */
    public boolean reset(StartFrom from) throws SQLException {
        requireNonNull(from, "No from provided");
        try (var ignored = gate.enter()) {
            return resetStopped(from);
        }
    }

    private boolean resetStopped(StartFrom from) throws SQLException {
        var wasRunning = isRunning();
        stopSubscription();
        var admin = eventLog.admin();
        long resumeFrom;
        if (from instanceof StartFrom.GlobalOrder order) {
            resumeFrom = order.value();
        } else {
            var row = admin.subscription(key());
            if (row.isEmpty()) {
                return false;
            }
            resumeFrom = row.get().resumePoint() + row.get().lag().orElse(0);
        }
        var reset = admin.reset(key(), resumeFrom);
        if (wasRunning) {
            subscribeIfNotRunning();
        }
        return reset;
    }

    private void handle(StoredEvent stored) throws Exception {
        var event    = Processors.deserialize(deserializer, stored, seams);
        var reaction = dispatch.handlerFor(event.payload().getClass());
        if (reaction.isEmpty()) {
            return;
        }
        try {
            reaction.get().react(event);
        } catch (Exception e) {
            if (!seams.has(IntegrationMutant.PROCESSOR_SWALLOWS_FAILURE)) {
                throw e;
            }
            log.warn("[{}] MUTANT PROCESSOR_SWALLOWS_FAILURE: failure of {} swallowed", definition.subscriber(), stored.globalOrder(), e);
        }
    }

    @Override
    public String toString() {
        return "EventReactor[" + definition.subscriber() + " on " + definition.aggregateType() + "]";
    }

    /** Reacts to one event */
    @FunctionalInterface
    public interface Reaction<T> {
        void react(DeliveredEvent event) throws Exception;
    }

    public static final class Builder {
        private final SubscriptionDefinition.Builder definition;
        private final String                         subscriber;
        private final Map<Class<?>, Reaction<?>>     reactions = new LinkedHashMap<>();
        private boolean                              strict;
        private ProcessorSeams                       seams     = ProcessorSeams.NONE;

        private Builder(String subscriber, String aggregateType) {
            this.subscriber = subscriber;
            this.definition = SubscriptionDefinition.builder(subscriber, aggregateType).consistency(ConsistencyMode.FRESH).exclusive();
        }

        /** React to payloads of {@code payloadType} and its subtypes */
        public <T> Builder on(Class<T> payloadType, Reaction<T> reaction) {
            requireNonNull(payloadType, "No payloadType provided");
            requireNonNull(reaction, "No reaction provided");
            if (reactions.putIfAbsent(payloadType, reaction) != null) {
                throw new IllegalArgumentException("A second reaction for " + payloadType.getName());
            }
            return this;
        }

        /** {@code FRESH} by default (D1) */
        public Builder consistency(ConsistencyMode consistency) {
            definition.consistency(consistency);
            return this;
        }

        public Builder ownership(Ownership ownership) {
            definition.ownership(ownership);
            return this;
        }

        public Builder startFrom(StartFrom startFrom) {
            definition.startFrom(startFrom);
            return this;
        }

        public Builder eventTypes(EventTypeFilter eventTypes) {
            definition.eventTypes(eventTypes);
            return this;
        }

        public Builder tenant(String tenant) {
            definition.tenant(tenant);
            return this;
        }

        public Builder errors(ErrorSettings errors) {
            definition.errors(errors);
            return this;
        }

        /** A payload type with no reaction is a handler failure, instead of counting as handled */
        public Builder strict() {
            this.strict = true;
            return this;
        }

        Builder seams(ProcessorSeams seams) {
            this.seams = requireNonNull(seams, "No seams provided");
            return this;
        }

        public EventReactor build(EventLog eventLog, JSONEventSerializer serializer) {
            return build(eventLog, EventDeserializer.of(serializer));
        }

        public EventReactor build(EventLog eventLog, EventDeserializer deserializer) {
            requireNonNull(eventLog, "No eventLog provided");
            requireNonNull(deserializer, "No deserializer provided");
            if (reactions.isEmpty()) {
                throw new IllegalStateException("An EventReactor needs at least one on(...)");
            }
            return new EventReactor(eventLog, definition.build(), deserializer, new Dispatch<>(reactions, strict, subscriber), seams);
        }
    }
}
