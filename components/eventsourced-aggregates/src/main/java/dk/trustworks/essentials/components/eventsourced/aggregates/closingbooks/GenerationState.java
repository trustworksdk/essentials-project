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

/**
 * Represents the state of a generation in the context of event-sourced aggregates.
 * A generation can either be in an open state, accepting writes, or in a closed state,
 * where it is considered finalized and no longer modifiable.
 * <p>
 * This enumeration is used to manage the lifecycle of aggregate generations,
 * particularly in systems where data consistency and state transitions are critical.
 */
public enum GenerationState {
    /**
     * Represents the "open" state in the lifecycle of an aggregate generation.
     * When a generation is in this state, it is active and allows updates or modifications.
     * This state signifies that the generation is not yet finalized.
     */
    OPEN,
    /**
     * Represents the "closed" state in the lifecycle of an aggregate generation.
     * When a generation is in this state, it is finalized and no longer accepts updates
     * or modifications. This state signifies that the generation is complete and no further
     * actions can be performed on it in terms of event sourcing or aggregation changes.
     */
    CLOSED
}
