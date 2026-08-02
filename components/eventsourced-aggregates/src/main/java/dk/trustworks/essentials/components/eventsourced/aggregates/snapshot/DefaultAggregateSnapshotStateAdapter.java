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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.components.foundation.json.Jackson3JSONSerializer;
import dk.trustworks.essentials.components.foundation.json.JacksonJSONSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Default adapter that snapshots only domain fields, while framework runtime state is restored from snapshot metadata.
 */
public class DefaultAggregateSnapshotStateAdapter implements AggregateSnapshotStateAdapter {
    private final JSONEventSerializer jsonSerializer;
    private final ObjenesisStd        objenesis = new ObjenesisStd();

    public DefaultAggregateSnapshotStateAdapter(JSONEventSerializer jsonSerializer) {
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
    }

    @Override
    public <AGGREGATE_IMPL_TYPE> String serializeSnapshotState(AGGREGATE_IMPL_TYPE aggregate) {
        requireNonNull(aggregate, "No aggregate provided");
        return jsonSerializer.serialize(extractDomainState(aggregate));
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> AGGREGATE_IMPL_TYPE deserializeSnapshotState(String serializedSnapshot,
                                                                                  Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                                                  ID aggregateId,
                                                                                  EventOrder eventOrderOfLastIncludedEvent) {
        requireNonNull(serializedSnapshot, "No serializedSnapshot provided");
        requireNonNull(aggregateImplType, "No aggregateImplType provided");
        requireNonNull(eventOrderOfLastIncludedEvent, "No eventOrderOfLastIncludedEvent provided");
        var aggregate = newAggregateInstance(aggregateImplType);
        SnapshotRuntimeStateSupport.restore(aggregate, aggregateId, eventOrderOfLastIncludedEvent);
        updateExistingAggregateInstance(aggregate, serializedSnapshot);
        return aggregate;
    }

    /**
     * Create a bare instance of the aggregate, bypassing constructors so aggregates with
     * required-arg constructors (no no-arg ctor / no @JsonCreator) work without forcing the
     * user to register {@code EssentialsImmutableJacksonModule}.
     * <p>
     * Strategy:
     * <ol>
     *     <li>Objenesis — bypasses constructors for any concrete non-abstract class. The
     *         common case for stateful aggregates.</li>
     *     <li>Fallback to {@link JSONEventSerializer#deserialize(String, Class)} with an empty
     *         JSON object — handles types Objenesis cannot instantiate (e.g. records when the
     *         JVM rejects bypass), provided Jackson can build a blank instance.</li>
     *     <li>If both fail, throw a clear error naming the actionable fixes.</li>
     * </ol>
     */
    private <T> T newAggregateInstance(Class<T> aggregateImplType) {
        if (aggregateImplType.isInterface() || Modifier.isAbstract(aggregateImplType.getModifiers())) {
            throw new IllegalArgumentException("Cannot deserialize snapshot into abstract type or interface '" + aggregateImplType.getName() + "'");
        }
        try {
            return objenesis.newInstance(aggregateImplType);
        } catch (Exception objenesisFailure) {
            try {
                return jsonSerializer.deserialize("{}", aggregateImplType);
            } catch (Exception jacksonFailure) {
                var error = new IllegalStateException(
                        "Failed to instantiate aggregate '" + aggregateImplType.getName() + "' for snapshot deserialization. " +
                                "Tried Objenesis (bypassing constructors) and Jackson with an empty JSON object. " +
                                "Ensure the aggregate is a concrete non-abstract class. If you cannot make Objenesis work " +
                                "(e.g. on restricted JVMs), provide a no-arg constructor, a @JsonCreator accepting an empty " +
                                "payload, or register EssentialsImmutableJacksonModule with your ObjectMapper.",
                        jacksonFailure);
                error.addSuppressed(objenesisFailure);
                throw error;
            }
        }
    }

    private void updateExistingAggregateInstance(Object aggregate, String serializedSnapshot) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> domainState = jsonSerializer.deserialize(serializedSnapshot, LinkedHashMap.class);
            applyDomainState(aggregate, domainState);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply snapshot state to aggregate " + aggregate.getClass().getName(), e);
        }
    }

    private void applyDomainState(Object target, Map<String, Object> domainState) throws IllegalAccessException {
        var valueConverter = valueConverter();
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!domainState.containsKey(field.getName())) {
                    continue;
                }
                if (!shouldApply(field)) {
                    continue;
                }
                field.setAccessible(true);
                var rawValue = domainState.get(field.getName());
                if (rawValue == null) {
                    field.set(target, null);
                    continue;
                }
                if (isAggregateStateField(field) && rawValue instanceof Map<?, ?> nestedStateMap) {
                    var existingState = field.get(target);
                    if (existingState == null) {
                        field.set(target, convertFieldValue(field, rawValue, valueConverter));
                    } else {
                        @SuppressWarnings("unchecked")
                        var nestedDomainState = (Map<String, Object>) nestedStateMap;
                        applyDomainState(existingState, nestedDomainState);
                    }
                } else {
                    field.set(target, convertFieldValue(field, rawValue, valueConverter));
                }
            }
            type = type.getSuperclass();
        }
    }

    private Object convertFieldValue(Field field, Object rawValue, SnapshotValueConverter valueConverter) {
        if (rawValue instanceof Map<?, ?> rawMap && Map.class.isAssignableFrom(field.getType())) {
            return convertMapFieldValue(field, rawMap, valueConverter);
        }
        return valueConverter.convert(rawValue, field.getGenericType());
    }

    private Object convertMapFieldValue(Field field, Map<?, ?> rawMap, SnapshotValueConverter valueConverter) {
        if (field.getGenericType() instanceof ParameterizedType parameterizedType && parameterizedType.getActualTypeArguments().length == 2) {
            var keyType   = parameterizedType.getActualTypeArguments()[0];
            var valueType = parameterizedType.getActualTypeArguments()[1];
            var convertedMap = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> convertedMap.put(valueConverter.convert(key, keyType),
                                                            valueConverter.convert(value, valueType)));
            return convertedMap;
        }
        return valueConverter.convert(rawMap, field.getGenericType());
    }

    /**
     * Converts a raw JSON value (as produced by binding the snapshot to a {@link Map}) into the target field's type,
     * using the Jackson mapper of whichever flavour the {@link JSONEventSerializer} wraps. Both Jackson majors are
     * supported because a build picks one of them and the snapshot has to be readable under either.
     */
    @FunctionalInterface
    private interface SnapshotValueConverter {
        Object convert(Object rawValue, Type targetType);
    }

    private SnapshotValueConverter valueConverter() {
        if (jsonSerializer instanceof JacksonJSONSerializer jacksonJSONSerializer) {
            var objectMapper = jacksonJSONSerializer.getObjectMapper();
            return (rawValue, targetType) -> objectMapper.convertValue(rawValue, objectMapper.constructType(targetType));
        }
        if (jsonSerializer instanceof Jackson3JSONSerializer jackson3JSONSerializer) {
            var objectMapper = jackson3JSONSerializer.getObjectMapper();
            return (rawValue, targetType) -> objectMapper.convertValue(rawValue, objectMapper.constructType(targetType));
        }
        throw new IllegalStateException("DefaultAggregateSnapshotStateAdapter requires a Jackson-based JSONEventSerializer, but got " + jsonSerializer.getClass().getName());
    }

    private Map<String, Object> extractDomainState(Object aggregate) {
        var state = new LinkedHashMap<String, Object>();
        Class<?> type = aggregate.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!shouldInclude(field)) {
                    if (isAggregateStateField(field)) {
                        state.put(field.getName(), extractAggregateState(field, aggregate));
                    }
                    continue;
                }
                try {
                    field.setAccessible(true);
                    state.put(field.getName(), field.get(aggregate));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Failed to read snapshot field '" + field.getName() + "' from " + aggregate.getClass().getName(), e);
                }
            }
            type = type.getSuperclass();
        }
        return state;
    }

    private boolean shouldInclude(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
            return false;
        }
        return !SnapshotRuntimeStateSupport.isFrameworkRuntimeDeclaringType(field.getDeclaringClass());
    }

    /**
     * The mirror of {@link #shouldInclude(Field)} for the deserialization side.
     * <p>
     * Framework runtime state is restored from the snapshot metadata by
     * {@link SnapshotRuntimeStateSupport#restore(Object, Object, EventOrder)}, never from the snapshot body — a
     * snapshot that does carry those fields (one written by serializing the whole aggregate rather than through
     * {@link #serializeSnapshotState(Object)}) would otherwise overwrite that restored state with the raw JSON values,
     * e.g. replacing the typed aggregate id with the plain string it was persisted as, or reinstating events that were
     * already committed.
     * <p>
     * Aggregate-state fields are the exception, exactly as in {@link #shouldInclude(Field)}: they are declared by the
     * framework but hold domain state.
     */
    private boolean shouldApply(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
            return false;
        }
        return isAggregateStateField(field) || !SnapshotRuntimeStateSupport.isFrameworkRuntimeDeclaringType(field.getDeclaringClass());
    }

    private boolean isAggregateStateField(Field field) {
        return field.getType() != null &&
                (dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateState.class.isAssignableFrom(field.getType()) ||
                 dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.state.AggregateState.class.isAssignableFrom(field.getType()));
    }

    private Object extractAggregateState(Field field, Object aggregate) {
        try {
            field.setAccessible(true);
            var value = field.get(aggregate);
            return value == null ? null : extractDomainState(value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to read snapshot state field '" + field.getName() + "' from " + aggregate.getClass().getName(), e);
        }
    }

    static final class SnapshotRuntimeStateSupport {
        private SnapshotRuntimeStateSupport() {
        }

        static boolean isFrameworkRuntimeDeclaringType(Class<?> declaringClass) {
            return declaringClass == AggregateRoot.class ||
                    declaringClass == dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.AggregateRoot.class ||
                    declaringClass == dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.state.AggregateState.class ||
                    declaringClass == dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateState.class;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        static <ID> void restore(Object aggregate, ID aggregateId, EventOrder lastIncludedEventOrder) {
            if (aggregate instanceof AggregateRoot modernAggregateRoot) {
                modernAggregateRoot.restoreSnapshotRuntimeState(aggregateId, lastIncludedEventOrder);
            } else if (aggregate instanceof dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.AggregateRoot classicAggregateRoot) {
                classicAggregateRoot.restoreSnapshotRuntimeState(aggregateId, lastIncludedEventOrder);
            } else if (aggregate instanceof dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.state.AggregateState aggregateState) {
                aggregateState.restoreSnapshotRuntimeState(aggregateId, lastIncludedEventOrder);
            }
        }
    }
}
