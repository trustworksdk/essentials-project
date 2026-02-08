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

package dk.trustworks.essentials.components.eventsourced.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;

import java.util.UUID;

/**
 * Common interface that all concrete (classical) {@link Aggregate}'s must implement. Most concrete implementations choose to extend the {@link AggregateRoot} class.
 *
 * @param <ID> the aggregate id (or stream-id) type
 *             <p>
 *             In event sourcing, an Aggregate-Id is a unique identifier that groups together related events belonging to the same business entity (aggregate). It plays a crucial role in:
 *             <ul>
 *               <li><b>Event Organization</b>: All events related to a specific aggregate instance share the same Aggregate-Id, allowing for easy tracking and retrieval of an aggregate's complete history.</li>
 *               <li><b>Stream Identification</b>: The Aggregate-Id helps identify which event stream an event belongs to, making it possible to rebuild the aggregate's state by replaying all events with the same ID.</li>
 *               <li><b>Concurrency Control</b>: Used to ensure that events for the same aggregate instance are processed in the correct order and to detect potential conflicts.</li>
 *             </ul>
 *
 *             <p>
 *                 <b>IMPORTANT</b>: For security reasons, Aggregate-Id's should:
 *             </p>
 *             <ul>
 *               <li>Be generated using secure methods (e.g., {@link RandomIdGenerator#generate()} or {@link UUID#randomUUID()})</li>
 *               <li>Never contain user-supplied input without proper validation</li>
 *               <li>Use safe characters to prevent SQL injection attacks when used in database operations that perform SQL string concatenation</li>
 *             </ul>
 * @see AggregateRoot
 */
public interface Aggregate<ID, AGGREGATE_TYPE extends Aggregate<ID, AGGREGATE_TYPE>> {
    /**
     * The id of the aggregate (aka. the stream-id)
     * <p>
     * In event sourcing, an Aggregate-Id is a unique identifier that groups together related events belonging to the same business entity (aggregate). It plays a crucial role in:
     * <ul>
     *   <li><b>Event Organization</b>: All events related to a specific aggregate instance share the same Aggregate-Id, allowing for easy tracking and retrieval of an aggregate's complete history.</li>
     *   <li><b>Stream Identification</b>: The Aggregate-Id helps identify which event stream an event belongs to, making it possible to rebuild the aggregate's state by replaying all events with the same ID.</li>
     *   <li><b>Concurrency Control</b>: Used to ensure that events for the same aggregate instance are processed in the correct order and to detect potential conflicts.</li>
     * </ul>
     *
     * <p>
     *     <b>IMPORTANT</b>: For security reasons, Aggregate-Id's should:
     * </p>
     * <ul>
     *   <li>Be generated using secure methods (e.g., {@link RandomIdGenerator#generate()} or {@link UUID#randomUUID()})</li>
     *   <li>Never contain user-supplied input without proper validation</li>
     *   <li>Use safe characters to prevent SQL injection attacks when used in database operations that perform SQL string concatenation</li>
     * </ul>
     */
    ID aggregateId();

    /**
     * Has the aggregate been initialized using previously recorded/persisted events (aka. historic events) using the {@link #rehydrate(AggregateEventStream)} method
     */
    boolean hasBeenRehydrated();

    /**
     * Effectively performs a leftFold over all the previously persisted events related to this aggregate instance
     *
     * @param persistedEvents the previous persisted events related to this aggregate instance, aka. the aggregates history
     * @return the same aggregate instance (self)
     */
    @SuppressWarnings("unchecked")
    AGGREGATE_TYPE rehydrate(AggregateEventStream<ID> persistedEvents);

    /**
     * Get the eventOrder of the last event during aggregate hydration (using the {@link #rehydrate(AggregateEventStream)} method)
     *
     * @return the event order of the last applied {@link Event} or {@link EventOrder#NO_EVENTS_PREVIOUSLY_PERSISTED} in case no
     * events has ever been applied to the aggregate
     */
    EventOrder eventOrderOfLastRehydratedEvent();
}
