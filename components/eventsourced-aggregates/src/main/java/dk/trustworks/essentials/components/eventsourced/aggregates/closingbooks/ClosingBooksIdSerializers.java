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

import dk.trustworks.essentials.shared.reflection.Reflector;
import dk.trustworks.essentials.types.SingleValueType;

import java.lang.reflect.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Resolution of a {@link ClosingBooksIdSerializer} from an id type alone - the machinery behind
 * {@link ClosingBooksIdSerializer#forType(Class)}.
 * <p>
 * Package-private on purpose: the supported id shapes are part of {@link ClosingBooksIdSerializer}'s documented
 * contract, but how they are detected is not.
 */
final class ClosingBooksIdSerializers {
    /**
     * How a {@link SingleValueType}'s non-{@link CharSequence} value is recovered from its persisted string form. The
     * value has to be parsed into the declared value type <em>before</em>
     * {@link SingleValueType#fromObject(Object, Class)} is called, because that method looks for a creator accepting the
     * value type - hand it a {@code String} for a {@code LongType}-backed id and it finds nothing.
     */
    private static final Map<Class<?>, Function<String, Object>> VALUE_PARSERS = Map.ofEntries(
            Map.entry(Long.class, Long::valueOf),
            Map.entry(Integer.class, Integer::valueOf),
            Map.entry(Short.class, Short::valueOf),
            Map.entry(Byte.class, Byte::valueOf),
            Map.entry(Double.class, Double::valueOf),
            Map.entry(Float.class, Float::valueOf),
            Map.entry(Boolean.class, Boolean::valueOf),
            Map.entry(BigDecimal.class, BigDecimal::new),
            Map.entry(BigInteger.class, BigInteger::new),
            Map.entry(UUID.class, UUID::fromString),
            Map.entry(Instant.class, Instant::parse),
            Map.entry(LocalDate.class, LocalDate::parse),
            Map.entry(LocalDateTime.class, LocalDateTime::parse),
            Map.entry(LocalTime.class, LocalTime::parse),
            Map.entry(OffsetDateTime.class, OffsetDateTime::parse),
            Map.entry(ZonedDateTime.class, ZonedDateTime::parse));

    private ClosingBooksIdSerializers() {
    }

    @SuppressWarnings("unchecked")
    static <ID> ClosingBooksIdSerializer<ID> forType(Class<ID> idType) {
        requireNonNull(idType, "No idType provided");

        if (idType.equals(String.class) || idType.equals(CharSequence.class)) {
            return (ClosingBooksIdSerializer<ID>) ClosingBooksIdSerializer.stringBased();
        }
        if (idType.equals(UUID.class)) {
            return (ClosingBooksIdSerializer<ID>) ClosingBooksIdSerializer.of(UUID::toString, UUID::fromString);
        }
        if (idType.isEnum()) {
            return enumSerializer(idType);
        }
        if (SingleValueType.class.isAssignableFrom(idType)) {
            return singleValueTypeSerializer(idType);
        }
        return reflectiveSerializer(idType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <ID> ClosingBooksIdSerializer<ID> enumSerializer(Class<ID> idType) {
        return ClosingBooksIdSerializer.of(id -> ((Enum<?>) id).name(),
                                           persisted -> (ID) Enum.valueOf((Class<? extends Enum>) idType, persisted));
    }

    @SuppressWarnings("unchecked")
    private static <ID> ClosingBooksIdSerializer<ID> singleValueTypeSerializer(Class<ID> idType) {
        var concreteType = (Class<? extends SingleValueType<?, ?>>) idType;
        var valueType = resolveSingleValueTypeValueType(idType)
                .orElseThrow(() -> new IllegalArgumentException(msg("Cannot derive a ClosingBooksIdSerializer for SingleValueType '{}': the value type it wraps could not be resolved from its generic hierarchy. " +
                                                                    "Pass an explicit serializer via ClosingBooksIdSerializer.of(...) instead",
                                                                    idType.getName())));

        if (CharSequence.class.isAssignableFrom(valueType)) {
            requireCreatorAccepting(idType, valueType);
            return ClosingBooksIdSerializer.of(Object::toString,
                                               persisted -> (ID) SingleValueType.fromObject(persisted, concreteType));
        }

        var parser = VALUE_PARSERS.get(valueType);
        if (parser == null) {
            throw new IllegalArgumentException(msg("Cannot derive a ClosingBooksIdSerializer for SingleValueType '{}': its value type '{}' cannot be parsed from a String. " +
                                                    "Supported value types are {}, or pass an explicit serializer via ClosingBooksIdSerializer.of(...)",
                                                    idType.getName(),
                                                    valueType.getName(),
                                                    supportedValueTypeNames()));
        }
        requireCreatorAccepting(idType, valueType);
        return ClosingBooksIdSerializer.of(id -> ((SingleValueType<?, ?>) id).value().toString(),
                                           persisted -> (ID) SingleValueType.fromObject(parser.apply(persisted), concreteType));
    }

    /**
     * The generic fallback: any type that can be turned back from its {@code toString()} through one of the three
     * creator shapes {@link SingleValueType#fromObject(Object, Class)} also looks for.
     */
    private static <ID> ClosingBooksIdSerializer<ID> reflectiveSerializer(Class<ID> idType) {
        var creator = findCreatorAccepting(idType, String.class)
                .orElseThrow(() -> new IllegalArgumentException(msg("Cannot derive a ClosingBooksIdSerializer for '{}': found none of a {}(String) constructor, a static {} of(String), or a static {} from(String). " +
                                                                    "Pass an explicit serializer via ClosingBooksIdSerializer.of(serialize, deserialize) instead",
                                                                    idType.getName(),
                                                                    idType.getSimpleName(),
                                                                    idType.getSimpleName(),
                                                                    idType.getSimpleName())));
        return ClosingBooksIdSerializer.of(Object::toString, creator::create);
    }

    /**
     * Fails at {@link ClosingBooksIdSerializer#forType(Class)} time rather than at the first deserialize, which would
     * otherwise happen during a generation resolve - potentially long after startup.
     */
    private static void requireCreatorAccepting(Class<?> idType, Class<?> valueType) {
        if (findCreatorAccepting(idType, valueType).isEmpty()) {
            throw new IllegalArgumentException(msg("Cannot derive a ClosingBooksIdSerializer for '{}': found none of a {}({}) constructor, a static {} of({}), or a static {} from({}). " +
                                                    "Pass an explicit serializer via ClosingBooksIdSerializer.of(serialize, deserialize) instead",
                                                    idType.getName(),
                                                    idType.getSimpleName(),
                                                    valueType.getSimpleName(),
                                                    idType.getSimpleName(),
                                                    valueType.getSimpleName(),
                                                    idType.getSimpleName(),
                                                    valueType.getSimpleName()));
        }
    }

    /**
     * Searches the same three shapes, in the same order, as {@link SingleValueType#fromObject(Object, Class)}:
     * constructor, then static {@code of}, then static {@code from}.
     */
    @SuppressWarnings("unchecked")
    private static <ID> Optional<Creator<ID>> findCreatorAccepting(Class<ID> idType, Class<?> valueType) {
        var reflector = Reflector.reflectOn(idType);
        if (reflector.hasMatchingConstructorBasedOnParameterTypes(valueType)) {
            return Optional.of(value -> reflector.newInstance(value));
        }
        for (var factoryMethodName : List.of("of", "from")) {
            var method = reflector.findMatchingMethod(factoryMethodName, true, valueType);
            if (method.isPresent()) {
                return Optional.of(value -> (ID) reflector.invokeStatic(method.get(), value));
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the {@code VALUE_TYPE} a concrete {@link SingleValueType} binds, by walking up its generic hierarchy and
     * substituting type variables as it goes. Needed because the value type is declared several levels up - e.g.
     * {@code OrderId extends LongType<OrderId>}, {@code LongType<C> extends NumberType<Long, C>},
     * {@code NumberType<N, C> implements SingleValueType<N, C>} - so neither the class itself nor its direct superclass
     * names it, and {@code value()} erases to {@link Number}.
     */
    private static Optional<Class<?>> resolveSingleValueTypeValueType(Class<?> idType) {
        Map<TypeVariable<?>, Type> typeVariableBindings = new HashMap<>();
        Class<?>                   current              = idType;

        while (current != null && !current.equals(Object.class)) {
            for (var genericInterface : current.getGenericInterfaces()) {
                if (genericInterface instanceof ParameterizedType parameterizedInterface
                        && SingleValueType.class.equals(parameterizedInterface.getRawType())) {
                    return asClass(resolve(parameterizedInterface.getActualTypeArguments()[0], typeVariableBindings));
                }
            }

            var genericSuperclass = current.getGenericSuperclass();
            if (genericSuperclass instanceof ParameterizedType parameterizedSuperclass) {
                var rawSuperclass  = (Class<?>) parameterizedSuperclass.getRawType();
                var typeParameters = rawSuperclass.getTypeParameters();
                var typeArguments  = parameterizedSuperclass.getActualTypeArguments();
                for (var index = 0; index < typeParameters.length && index < typeArguments.length; index++) {
                    typeVariableBindings.put(typeParameters[index], resolve(typeArguments[index], typeVariableBindings));
                }
                current = rawSuperclass;
            } else {
                current = current.getSuperclass();
            }
        }
        return Optional.empty();
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> typeVariableBindings) {
        var resolved = type;
        while (resolved instanceof TypeVariable<?> typeVariable && typeVariableBindings.containsKey(typeVariable)) {
            var next = typeVariableBindings.get(typeVariable);
            if (next.equals(resolved)) {
                break;
            }
            resolved = next;
        }
        return resolved;
    }

    private static Optional<Class<?>> asClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return Optional.of(clazz);
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawType) {
            return Optional.of(rawType);
        }
        return Optional.empty();
    }

    private static List<String> supportedValueTypeNames() {
        return VALUE_PARSERS.keySet()
                            .stream()
                            .map(Class::getSimpleName)
                            .sorted()
                            .toList();
    }

    @FunctionalInterface
    private interface Creator<ID> {
        ID create(Object value);
    }
}
