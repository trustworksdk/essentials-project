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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A serializer interface for handling the conversion of {@link LogicalAggregateId} objects
 * to and from their serialized string representation.
 *
 * @param <ID> the type of the identifier for the logical business aggregate
 */
public interface ClosingBooksLogicalAggregateIdSerializer<ID> {

    /**
     * Serializes the given {@link LogicalAggregateId} instance into its string representation.
     *
     * @param logicalAggregateId the {@link LogicalAggregateId} instance to serialize; must not be null
     * @return the string representation of the provided {@link LogicalAggregateId}
     */
    String serialize(LogicalAggregateId<ID> logicalAggregateId);

    /**
     * Deserializes the provided string representation into a {@link LogicalAggregateId} instance.
     *
     * @param serializedLogicalAggregateId the string representation of a {@link LogicalAggregateId};
     *                                      must not be null
     * @return a {@link LogicalAggregateId} instance corresponding to the provided serialized string
     */
    LogicalAggregateId<ID> deserialize(String serializedLogicalAggregateId);

    static ClosingBooksLogicalAggregateIdSerializer<String> stringBased() {
        return new ClosingBooksLogicalAggregateIdSerializer<>() {
            @Override
            public String serialize(LogicalAggregateId<String> logicalAggregateId) {
                requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
                return logicalAggregateId.toString();
            }

            @Override
            public LogicalAggregateId<String> deserialize(String serializedLogicalAggregateId) {
                requireNonNull(serializedLogicalAggregateId, "No serializedLogicalAggregateId provided");
                return new LogicalAggregateId<>(serializedLogicalAggregateId);
            }
        };
    }
}
