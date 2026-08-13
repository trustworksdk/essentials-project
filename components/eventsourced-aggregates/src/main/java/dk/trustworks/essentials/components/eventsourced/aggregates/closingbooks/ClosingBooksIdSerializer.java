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

import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Bidirectional {@code ID <-> String} mapping used by the closing-books types for every id they persist as text.
 * <p>
 * Both closing-books id roles use this same interface:
 * <ul>
 *     <li><b>logical aggregate ids</b> — the stable business id of an aggregate across all its generations, persisted
 *     in the {@code logical_aggregate_id} column by {@link PostgresqlClosingBooksGenerationRepository}. Because that
 *     role deals in {@link LogicalAggregateId} wrappers rather than bare ids, it uses
 *     {@link #serializeLogicalAggregateId(LogicalAggregateId)} / {@link #deserializeLogicalAggregateId(String)}, which
 *     are derived from {@link #serialize(Object)} / {@link #deserialize(String)} and are never implemented by hand;</li>
 *     <li><b>generation stream ids</b> — the id of the event stream backing one generation, produced by a
 *     {@link ClosingBooksStreamIdGenerator} and turned back into a typed stream id by
 *     {@link ClosingBooksLogicalAggregateRepository}.</li>
 * </ul>
 * An implementation therefore only ever has to describe how a single id value maps to and from its persisted string
 * form.
 *
 * @param <ID> the id type being serialized — a logical aggregate id or a generation stream id
 */
public interface ClosingBooksIdSerializer<ID> {

    /**
     * Serializes the given id into the string form that is persisted.
     *
     * @param id the id to serialize; must not be null
     * @return the persisted string representation of the id
     */
    String serialize(ID id);

    /**
     * Deserializes a persisted string representation back into a typed id.
     *
     * @param persistedId the persisted string representation of the id; must not be null
     * @return the typed id corresponding to the provided string
     */
    ID deserialize(String persistedId);

    /**
     * Serializes the id inside the given {@link LogicalAggregateId} using {@link #serialize(Object)}.
     *
     * @param logicalAggregateId the {@link LogicalAggregateId} whose value should be serialized; must not be null
     * @return the persisted string representation of the wrapped id
     */
    default String serializeLogicalAggregateId(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return serialize(logicalAggregateId.value());
    }

    /**
     * Deserializes a persisted logical aggregate id using {@link #deserialize(String)} and wraps the result in a
     * {@link LogicalAggregateId}.
     *
     * @param persistedLogicalAggregateId the persisted string representation of a logical aggregate id;
     *                                    must not be null
     * @return the {@link LogicalAggregateId} corresponding to the provided string
     */
    default LogicalAggregateId<ID> deserializeLogicalAggregateId(String persistedLogicalAggregateId) {
        requireNonNull(persistedLogicalAggregateId, "No persistedLogicalAggregateId provided");
        return new LogicalAggregateId<>(deserialize(persistedLogicalAggregateId));
    }

    /**
     * Serializer built from the two directions as functions - the shortest way to describe an id type the framework
     * cannot derive on its own.
     *
     * @param serialize   maps an id to its persisted string form; must not be null
     * @param deserialize maps a persisted string back to an id; must not be null
     * @param <ID>        the id type
     * @return a {@link ClosingBooksIdSerializer} over the two functions
     */
    static <ID> ClosingBooksIdSerializer<ID> of(Function<ID, String> serializeFunction,
                                                Function<String, ID> deserializeFunction) {
        requireNonNull(serializeFunction, "No serializeFunction provided");
        requireNonNull(deserializeFunction, "No deserializeFunction provided");
        return new ClosingBooksIdSerializer<>() {
            @Override
            public String serialize(ID id) {
                requireNonNull(id, "No id provided");
                return serializeFunction.apply(id);
            }

            @Override
            public ID deserialize(String persistedId) {
                requireNonNull(persistedId, "No persistedId provided");
                return deserializeFunction.apply(persistedId);
            }
        };
    }

    /**
     * Derives a serializer from the id type alone. This is the common case in an Essentials application, where an id is
     * a {@link dk.trustworks.essentials.types.SingleValueType} and the mapping is mechanical.
     * <p>
     * Supported id types:
     * <table>
     *     <caption>Id type to serialization strategy</caption>
     *     <tr><th>Id type</th><th>Serialize</th><th>Deserialize</th></tr>
     *     <tr><td>{@link String} / {@link CharSequence}</td><td>identity</td><td>identity</td></tr>
     *     <tr><td>{@link java.util.UUID}</td><td>{@code toString()}</td><td>{@code UUID.fromString(…)}</td></tr>
     *     <tr><td>{@code enum}</td><td>{@code name()}</td><td>{@code Enum.valueOf(…)}</td></tr>
     *     <tr><td>{@link dk.trustworks.essentials.types.SingleValueType} over a {@link CharSequence}</td>
     *         <td>{@code toString()}</td><td>{@code SingleValueType.fromObject(persisted, type)}</td></tr>
     *     <tr><td>{@link dk.trustworks.essentials.types.SingleValueType} over a non-string value
     *             ({@link Long}, {@link Integer}, {@link java.math.BigDecimal}, {@link java.util.UUID}, a JSR-310 type, …)</td>
     *         <td>{@code value().toString()}</td>
     *         <td>parse into the declared value type, then {@code SingleValueType.fromObject(…)}</td></tr>
     *     <tr><td>anything else</td><td>{@code toString()}</td>
     *         <td>a {@code (String)} constructor, static {@code of(String)}, or static {@code from(String)}</td></tr>
     * </table>
     * The strategy is resolved once, here, and an id type that cannot be handled fails immediately with a message
     * naming the type and the shapes that were searched for. That matters: the first deserialize happens during a
     * generation resolve, potentially long after startup.
     *
     * @param idType the id type; must not be null
     * @param <ID>   the id type
     * @return a {@link ClosingBooksIdSerializer} for the given id type
     * @throws IllegalArgumentException if no strategy can be derived for the id type
     */
    static <ID> ClosingBooksIdSerializer<ID> forType(Class<ID> idType) {
        return ClosingBooksIdSerializers.forType(idType);
    }

    /**
     * Serializer for ids that are already {@link String}s — both directions are the identity.
     *
     * @return a {@link ClosingBooksIdSerializer} over {@link String} ids
     */
    static ClosingBooksIdSerializer<String> stringBased() {
        return new ClosingBooksIdSerializer<>() {
            @Override
            public String serialize(String id) {
                return requireNonNull(id, "No id provided");
            }

            @Override
            public String deserialize(String persistedId) {
                return requireNonNull(persistedId, "No persistedId provided");
            }
        };
    }
}
