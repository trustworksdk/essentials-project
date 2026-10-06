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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.shared.FailFast;
import dk.trustworks.essentials.types.*;

import java.lang.reflect.Modifier;
import java.util.UUID;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Encapsulates the Aggregate id type used for a given {@link AggregateEventStream}
 * and how a typed instance of the Aggregate id (or Stream Id) can be serialized to a
 * single string value that fits into the {@link EventStore}'s
 * concept of storing aggregate id's as single column that contains a String value.<br>
 * The actual Postgresql column type is determined by {@link AggregateEventStreamConfiguration#aggregateIdColumnType}<br>
 * <br>
 * If your aggregate id is a {@link String}, {@link UUID}, a subtype of {@link CharSequenceType} or a Kotlin
 * {@code dk.trustworks.essentials.kotlin.types.StringValueType} (served by the Kotlin
 * {@code StringValueTypeAggregateIdSerializer}) then you
 * can use {@link #serializerFor(Class)} to look up an {@link AggregateIdSerializer} for your aggregate id.
 *
 * @see StringIdSerializer
 * @see UUIDIdSerializer
 * @see CharSequenceTypeIdSerializer
 */
public interface AggregateIdSerializer {
    /**
     * Helper factory method to lookup a standard {@link AggregateIdSerializer}'s based on
     * the type of aggregate id
     *
     * @param aggregateIdType the aggregate id type
     * @return the best fitting standard {@link AggregateIdSerializer} based on the type
     * (a {@code StringValueTypeAggregateIdSerializer} for a Kotlin {@code StringValueType})
     * @throws EventStoreException in case a matching {@link AggregateIdSerializer} cannot be found
     */
    @SuppressWarnings("unchecked")
    static AggregateIdSerializer serializerFor(Class<?> aggregateIdType) {
        requireNonNull(aggregateIdType, "No aggregateIdType provided");
        if (CharSequenceType.class.isAssignableFrom(aggregateIdType)) {
            return new CharSequenceTypeIdSerializer((Class<? extends CharSequenceType<?>>) aggregateIdType);
        }
        if (String.class.isAssignableFrom(aggregateIdType)) {
            return new StringIdSerializer();
        }
        if (UUID.class.isAssignableFrom(aggregateIdType)) {
            return new UUIDIdSerializer();
        }
        if (isKotlinStringValueType(aggregateIdType)) {
            return StringValueTypeAggregateIdSerializer.forType(aggregateIdType);
        }
        throw new EventStoreException(msg("Couldn't find a matching {} for {}. serializerFor supports String, UUID, " +
                                                  "subtypes of {} and, for Kotlin, subtypes of " +
                                                  "dk.trustworks.essentials.kotlin.types.StringValueType (served by " +
                                                  "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.StringValueTypeAggregateIdSerializer, " +
                                                  "which can also be constructed explicitly); for any other id type provide your own {} implementation",
                                          AggregateIdSerializer.class.getName(),
                                          aggregateIdType.getName(),
                                          CharSequenceType.class.getName(),
                                          AggregateIdSerializer.class.getSimpleName()));
    }

    /**
     * Matches the Kotlin {@code StringValueType} by name, and only once every Java id type has been ruled out: the
     * serializer it maps to needs the Kotlin standard library and kotlin-reflect, which are {@code provided}, so a
     * Java-only classpath must never load it. That is also why the check and the error message name the Kotlin types
     * as strings rather than class literals.
     */
    private static boolean isKotlinStringValueType(Class<?> aggregateIdType) {
        try {
            return Class.forName("dk.trustworks.essentials.kotlin.types.StringValueType", false, aggregateIdType.getClassLoader())
                        .isAssignableFrom(aggregateIdType);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * The Java type of the Aggregate-Id/Stream-Id
     */
    Class<?> aggregateIdType();

    /**
     * Serializes a typed aggregate id instance to a String
     *
     * @param aggregateId the aggregate id
     * @return the string version of the aggregate id
     */
    String serialize(Object aggregateId);

    /**
     * Deserializes a string version of an aggregate id to the typed
     * aggregate id
     *
     * @param aggregateId the string version of the aggregate id
     * @return the type version of the aggregate id
     */
    Object deserialize(String aggregateId);

    /**
     * Serializer/Deserializer for Aggregate Id's that are String's
     */
    class StringIdSerializer implements AggregateIdSerializer {

        @Override
        public Class<?> aggregateIdType() {
            return String.class;
        }

        @Override
        public String serialize(Object aggregateId) {
            requireMustBeInstanceOf(aggregateId, String.class);
            return (String) aggregateId;
        }

        @Override
        public Object deserialize(String aggregateId) {
            return aggregateId;
        }
    }

    /**
     * Serializer/Deserializer for Aggregate Id's that are {@link UUID}'s
     */
    class UUIDIdSerializer implements AggregateIdSerializer {

        @Override
        public Class<?> aggregateIdType() {
            return UUID.class;
        }

        @Override
        public String serialize(Object aggregateId) {
            requireMustBeInstanceOf(aggregateId, UUID.class);
            return aggregateId.toString();
        }

        @Override
        public Object deserialize(String aggregateId) {
            return UUID.fromString(aggregateId);
        }
    }

    /**
     * Serializer/Deserializer for Aggregate Id's that are subtypes of {@link CharSequenceType}
     */
    class CharSequenceTypeIdSerializer implements AggregateIdSerializer {
        private final Class<? extends CharSequenceType<?>> characterType;

        /**
         * Serializer/Deserializer for Aggregate Id's that are subtypes of {@link CharSequenceType}
         *
         * @param characterType the concrete {@link CharSequenceType} subtype that is used for the Aggregate Id that's being serialized/deserialized by this instance
         */
        public CharSequenceTypeIdSerializer(Class<? extends CharSequenceType<?>> characterType) {
            FailFast.requireNonNull(characterType);
            FailFast.requireFalse(Modifier.isAbstract(characterType.getModifiers()),
                                  msg("The provided CharacterType '{}' MUST be concrete", characterType.getName()));
            this.characterType = characterType;
        }

        @Override
        public Class<? extends CharSequenceType<?>> aggregateIdType() {
            return characterType;
        }

        @Override
        public String serialize(Object aggregateId) {
            requireMustBeInstanceOf(aggregateId, characterType);
            return aggregateId.toString();
        }

        @Override
        public Object deserialize(String aggregateId) {
            return SingleValueType.fromObject(aggregateId, characterType);
        }

        @Override
        public String toString() {
            return characterType.getName();
        }
    }
}
