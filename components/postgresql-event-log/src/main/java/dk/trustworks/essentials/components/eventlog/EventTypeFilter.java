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
package dk.trustworks.essentials.components.eventlog;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The event types a subscription consumes, by name. An
 * empty set means every event type ({@link #all()}). A row of another type counts as handled without reaching the
 * handler. Not persisted: changing a subscription's filter changes what it receives from its current position
 * on.
 *
 * @param eventTypes the event type names; empty for every event type
 */
public record EventTypeFilter(Set<String> eventTypes) {
    private static final EventTypeFilter ALL = new EventTypeFilter(Set.of());

    public EventTypeFilter {
        requireNonNull(eventTypes, "No eventTypes provided");
        eventTypes.forEach(name -> requireTrue(name != null && !name.isBlank(), "An event type name is blank"));
        eventTypes = Set.copyOf(eventTypes);
    }

    /** @return the filter that matches every event type */
    public static EventTypeFilter all() {
        return ALL;
    }

    /** @return a filter matching exactly {@code eventTypes}; at least one */
    public static EventTypeFilter of(String... eventTypes) {
        requireNonNull(eventTypes, "No eventTypes provided");
        requireTrue(eventTypes.length > 0, "At least one event type is required; use all() for every type");
        return new EventTypeFilter(new HashSet<>(Arrays.asList(eventTypes)));
    }

    /** @return whether this filter matches every event type */
    public boolean matchesAll() {
        return eventTypes.isEmpty();
    }

    /** @return whether an event of type {@code eventType} passes */
    public boolean matches(String eventType) {
        return eventTypes.isEmpty() || eventTypes.contains(eventType);
    }
}
