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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The key of one {@code el_progress} row: an exclusive
 * subscription's row has the empty instance name, a per-instance one its instance's name
 */
public record ProgressKey(String subscriber, String aggregateType, String instanceName) {
    public ProgressKey {
        requireNonNull(subscriber, "No subscriber provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(instanceName, "No instanceName provided");
    }

    /** @return the key of an exclusive subscription's row */
    public static ProgressKey exclusive(String subscriber, String aggregateType) {
        return new ProgressKey(subscriber, aggregateType, "");
    }

    /** @return the key of {@code definition}'s row */
    public static ProgressKey of(SubscriptionDefinition definition) {
        requireNonNull(definition, "No definition provided");
        return new ProgressKey(definition.subscriber(), definition.aggregateType(), definition.ownership().instanceName());
    }

    ProgressStore.Key internal() {
        return new ProgressStore.Key(subscriber, aggregateType, instanceName);
    }
}
