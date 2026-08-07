/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Resolves the {@link AggregateIdSerializer} configured for an {@link AggregateType}, so a CDC converter
 * can turn the raw {@code aggregate_id} text it reads out of the WAL back into the <em>typed</em> aggregate
 * id that {@link PersistedEvent#aggregateId()} is contracted to carry.
 * <p>
 * Why this exists: the polling path builds its {@link PersistedEvent}s through {@code PersistedEventRowMapper},
 * which deserializes the id column via the configured serializer. A CDC converter reads the same column
 * straight off a WAL row change, where it is only ever text. Without this resolver the two delivery paths
 * hand subscribers structurally different events — the same event arrives with a {@code TradeId} when polled
 * and a {@code String} when streamed. Anything that treats the id as its declared type then breaks under CDC
 * and only under CDC; {@code EventProcessor.forwardEventToInbox} does exactly that and throws
 * {@code Expected java.lang.String to be an instance of …Id} for every forwarded event.
 *
 * @see AggregateTypeResolver
 */
@FunctionalInterface
public interface AggregateIdSerializerResolver {
    /**
     * @param aggregateType the aggregate type whose id serializer is wanted
     * @return the configured serializer, or {@link Optional#empty()} if the aggregate type is not registered —
     * in which case the caller should leave the id as the raw text rather than guess at a type
     */
    Optional<AggregateIdSerializer> tryResolve(AggregateType aggregateType);

    /**
     * Resolver backed by the event store's stream configuration — the same source
     * {@code PersistedEventRowMapper} reads, which is what makes the two delivery paths agree.
     * <p>
     * The lookup happens per call rather than being snapshotted, so aggregate types registered at runtime
     * via {@code addAggregateEventStreamConfiguration(...)} are picked up without rebuilding anything.
     */
    static AggregateIdSerializerResolver forEventStore(ConfigurableEventStore<?> eventStore) {
        requireNonNull(eventStore, "eventStore cannot be null");
        return aggregateType -> eventStore.findAggregateEventStreamConfiguration(aggregateType)
                                          .map(configuration -> configuration.aggregateIdSerializer);
    }

    /**
     * Resolver that never resolves, leaving aggregate ids as the raw text read from the WAL.
     * <p>
     * This reproduces the behaviour CDC had before the typed-id fix and exists only so the deprecated
     * converter constructors keep their old semantics. Do not wire it into a running system: it is the
     * configuration in which CDC-delivered events disagree with polled ones.
     */
    static AggregateIdSerializerResolver rawText() {
        return aggregateType -> Optional.empty();
    }

    /**
     * Apply this resolver to a raw WAL {@code aggregate_id}, falling back to the raw value when the aggregate
     * type is unregistered or the text cannot be deserialized. Conversion must not fail over an id it cannot
     * type — that would quarantine an otherwise-valid event.
     */
    default Object deserializeOrRaw(AggregateType aggregateType, Object rawAggregateId) {
        if (!(rawAggregateId instanceof String rawText)) {
            return rawAggregateId;
        }
        try {
            return tryResolve(aggregateType)
                    .<Object>map(serializer -> serializer.deserialize(rawText))
                    .orElse(rawAggregateId);
        } catch (RuntimeException e) {
            return rawAggregateId;
        }
    }
}
