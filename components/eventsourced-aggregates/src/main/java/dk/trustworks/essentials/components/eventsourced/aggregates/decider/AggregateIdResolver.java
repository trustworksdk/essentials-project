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

package dk.trustworks.essentials.components.eventsourced.aggregates.decider;

import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;

import java.util.*;

/**
 * Interface responsible for resolve the optional aggregate id associated with the type <code>T</code><br>
 *
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
 * <p>
 * Example of usages:<br>
 * <ul>
 *     <li><b>Command</b> - return the aggregate id associated with the command (can return an {@link Optional#empty()})
 *     in case server generated id's are used for commands that create a new Aggregate instance)</li>
 *     <li><b>Event</b> - return the aggregate id associated with the event</li>
 *     <li><b>State</b> -  return the aggregate id associated with the aggregate state event projection</li>
 *     <li><b>View</b> -  return the aggregate id associated with the View event projection</li>
 * </ul>
 *
 * @param <T>  The type of object that this resolver support. Could e.g. be a <code>COMMAND</code>, <code>EVENT</code>, Aggregate <code>STATE</code>, <code>VIEW</code>, etc.
 * @param <ID> The type of Aggregate Id
 */
public interface AggregateIdResolver<T, ID> {
    /**
     * Resolve the optional aggregate id associated with the type <code>T</code><br>
     * Example of usages:<br>
     * <ul>
     *     <li><b>Command</b> - return the aggregate id associated with the command (can return an {@link Optional#empty()})
     *     in case server generated id's are used for commands that create a new Aggregate instance)</li>
     *     <li><b>Event</b> - return the aggregate id associated with the event</li>
     *     <li><b>State</b> -  return the aggregate id associated with the aggregate state event projection</li>
     *     <li><b>View</b> -  return the aggregate id associated with the View event projection</li>
     * </ul>
     *
     * @param t the instance of T
     * @return if the <code>t</code> contains an aggregate id then it MUST be returned
     * in an {@link Optional#of(Object)} otherwise an {@link Optional#empty()}
     */
    Optional<ID> resolveFrom(T t);
}
