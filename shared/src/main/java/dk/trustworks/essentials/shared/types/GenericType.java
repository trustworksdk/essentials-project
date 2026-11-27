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

package dk.trustworks.essentials.shared.types;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Using this class makes it possible to capture a generic/parameterized argument type, such as <code>List&lt;Money></code>,
 * instead of having to rely on the classical <code><b>.class</b></code> construct.<br>
 * When you specify a type reference using <code><b>.class</b></code> you loose any Generic/Parameterized information, as you cannot write <code>List&lt;Money>.class</code>,
 * only <code>List.class</code>.<br>
 * <br>
 * <b>With {@link GenericType} you can specify and capture parameterized type information</b><br>
 * <br>
 * <b>You can specify a parameterized type using {@link GenericType}:</b><br>
 * <blockquote>
 * <code>{@literal var genericType = new GenericType<List<Money>>(){};}</code><br>
 * <br>
 * The {@link GenericType#type}/{@link GenericType#getType()} will return <code>List.class</code><br>
 * And {@link GenericType#genericType}/{@link GenericType#getGenericType()} will return {@link ParameterizedType}, which can be introspected further<br>
 * </blockquote>
 * <br>
 * <br>
 * <b>You can also supply a non-parameterized type to {@link GenericType}:</b><br>
 * <blockquote>
 * <code>{@literal var genericType = new GenericType<Money>(){};}</code><br>
 * <br>
 * In which case {@link GenericType#type}/{@link GenericType#getType()} will return <code>Money.class</code><br>
 * And {@link GenericType#genericType}/{@link GenericType#getGenericType()} will return <code>Money.class</code>
 * </blockquote>
 *
 * @param <T> the type provided to the {@link GenericType}
 */
public abstract class GenericType<T> {
    public final Class<T> type;
    public final Type     genericType;

    @SuppressWarnings("unchecked")
    public GenericType() {
        var genericSuperClass = this.getClass().getGenericSuperclass();
        if (genericSuperClass instanceof ParameterizedType) {
            genericType = ((ParameterizedType) genericSuperClass).getActualTypeArguments()[0];
            if (genericType instanceof Class) {
                type = (Class<T>) genericType;
            } else {
                // Use the raw type
                type = (Class<T>) ((ParameterizedType) genericType).getRawType();
            }
        } else {
            throw new IllegalStateException(msg("No generic type information available on {}", this.getClass()));
        }
    }


    public Class<T> getType() {
        return type;
    }

    public Type getGenericType() {
        return genericType;
    }

    // ---------------------------------- Utility methods ------------------------------------------

    /**
     * Resolve the concrete parameterized type a given <code>forType</code> has specified with its direct super class.<br>
     * Example:<br>
     * <b>Given super-class:</b><br>
     * <code>{@literal class AggregateRoot<ID, AGGREGATE_TYPE extends AggregateRoot<ID, AGGREGATE_TYPE>> implements Aggregate<ID>}</code><br>
     * <br>
     * <b>And sub/concrete class:</b><br>
     * <code>{@literal class Order extends AggregateRoot<OrderId, Order>}</code><br>
     * <br>
     * <b>Then:</b><br>
     * <code>GenericType.resolveGenericType(Order.class, 0)</code> will return <code>OrderId.class</code><br>
     * <br>
     * <b>and</b><br>
     * <code>GenericType.resolveGenericType(Order.class, 1)</code> will return <code>Order.class</code><br>
     *
     * @param forType           the type that is specifying a parameterized type with its super-class
     * @param typeArgumentIndex the index in the superclasses type parameters (0 based)
     * @return an optional with the parameterized type or {@link Optional#empty()} if the type couldn't resolved
     */
    public static Class<?> resolveGenericTypeOnSuperClass(Class<?> forType, int typeArgumentIndex) {
        requireNonNull(forType, "No forType provided");
        var genericSuperClass = forType.getGenericSuperclass();
        if (genericSuperClass instanceof ParameterizedType) {
            var actualTypeArguments = ((ParameterizedType) genericSuperClass).getActualTypeArguments();
            if (actualTypeArguments.length <= typeArgumentIndex) {
                throw new IllegalStateException(msg("{} only has {} type arguments, cannot resolve type argument with index {}",
                                                    forType,
                                                    actualTypeArguments.length,
                                                    typeArgumentIndex));
            }
            var genericType = actualTypeArguments[typeArgumentIndex];
            if (genericType instanceof Class) {
                return (Class<?>) genericType;
            } else {
                // Use the raw type
                return (Class<?>) ((ParameterizedType) genericType).getRawType();
            }
        } else {
            throw new IllegalStateException(msg("No generic type information available on type '{}' for typeArgument with index {}",
                                                forType.getName(),
                                                typeArgumentIndex));
        }
    }

    /**
     * Resolve a specific parameterized type argument <code>typeArgumentIndex</code> for a given <code>forType</code> which implements a generic/parameterize interface <code>withGenericInterface</code><br>
     * Example:<br>
     * Given this <code>withGenericInterface</code>:
     * <pre>{@code
     * public interface WithState<ID, AGGREGATE_TYPE extends AggregateRoot<ID, AGGREGATE_TYPE>, AGGREGATE_STATE extends AggregateState<ID, AGGREGATE_TYPE>> {
     * }
     * }</pre>
     * and this <code>forType</code>:
     * <pre>{@code
     * public static class Order extends AggregateRoot<String, Order> implements WithState<String, Order, OrderState> {
     * }
     * }</pre>
     * <br>
     * then calling
     * <pre>{@code
     * var actualType = GenericType.resolveGenericTypeForInterface(Order.class,
     *                                                             WithState.class,
     *                                                             typeArgumentIndex);
     * }</pre>
     * with these <code>typeArgumentIndex</code> will yield the following results:
     * <table>
     *     <tr><td>typeArgumentIndex</td><td>Returned generic type</td></tr>
     *     <tr><td>0</td><td>String.class</td></tr>
     *     <tr><td>1</td><td>Order.class</td></tr>
     *     <tr><td>2</td><td>OrderState.class</td></tr>
     *     <tr><td>3</td><td>throws IllegalArgumentException</td></tr>
     * </table>
     *
     * @param forType              the type that implement the <code>withGenericInterface</code>
     * @param withGenericInterface the parameterized/generic interface that specifies a parameterized type at index <code>typeArgumentIndex</code>
     * @param typeArgumentIndex    the parameterized argument index (0 based)
     * @return the type
     * @throws IllegalStateException    in case the type doesn't extend the interface, the interface isn't generic
     * @throws IllegalArgumentException if the interface doesn't specify a parameterized argument with the specified index
     */
    public static Class<?> resolveGenericTypeForInterface(Class<?> forType, Class<?> withGenericInterface, int typeArgumentIndex) {
        var parameterizedGenericInterface = Stream.of(forType.getGenericInterfaces())
                                                  .filter(type -> type instanceof ParameterizedType)
                                                  .map(ParameterizedType.class::cast)
                                                  .filter(type -> withGenericInterface.equals(type.getRawType()) || withGenericInterface.isAssignableFrom((Class<?>) type.getRawType()))
                                                  .findFirst()
                                                  .orElseThrow(() -> new IllegalStateException(msg("{} doesn't implement generic Interface {} or " +
                                                                                                           "Interface {} isn't generic/parameterized",
                                                                                                   forType,
                                                                                                   withGenericInterface.getName(),
                                                                                                   withGenericInterface.getName())));

        var actualTypeArguments = parameterizedGenericInterface.getActualTypeArguments();
        if (actualTypeArguments.length <= typeArgumentIndex) {
            throw new IllegalArgumentException(msg("{} only has {} type arguments, cannot resolve type argument with index {}",
                                                   forType,
                                                   actualTypeArguments.length,
                                                   typeArgumentIndex));
        }
        var genericType = parameterizedGenericInterface.getActualTypeArguments()[typeArgumentIndex];
        if (genericType instanceof Class) {
            return (Class<?>) genericType;
        } else {
            // Use the raw type
            return (Class<?>) ((ParameterizedType) genericType).getRawType();
        }
    }


    /**
     * Resolve the type argument at <code>typeArgumentIndex</code> that <code>forType</code> gives the generic class or
     * interface <code>genericSupertype</code>, wherever in its type hierarchy that happens.<br>
     * Unlike {@link #resolveGenericTypeForInterface(Class, Class, int)}, which only reads the interfaces
     * <code>forType</code> itself declares, this walks every superclass and (sub-)interface and maps type variables
     * through each parameterization on the way. So it also resolves:
     * <ul>
     *     <li>a subclass of a type that implements <code>genericSupertype</code>:
     *     {@code class SpecialOrder extends Order}, where {@code Order implements WithState<String, Order, OrderState>}</li>
     *     <li>a type argument passed on through a generic base class:
     *     {@code abstract class Base<ID, S> implements WithState<ID, ..., S>} and {@code class Order extends Base<String, OrderState>}</li>
     *     <li>a sub-interface that declares its type parameters in another order than <code>genericSupertype</code></li>
     * </ul>
     * When more than one path leads to <code>genericSupertype</code>, the first that binds the argument to a type wins;
     * interfaces are tried before the superclass.
     *
     * @param forType           the type to resolve the type argument for
     * @param genericSupertype  the generic class or interface that declares the type parameter
     * @param typeArgumentIndex the index (0 based) of the type parameter in <code>genericSupertype</code>'s declaration
     * @return the raw class of the type argument (for a parameterized argument such as {@code List<String>}, its raw
     * type {@code List}); empty when <code>forType</code> does not extend or implement <code>genericSupertype</code>,
     * or when no path binds the argument to a type (it stays a type variable, for instance on a raw or still generic
     * type)
     * @throws IllegalArgumentException if <code>genericSupertype</code> declares no type parameter with that index
     */
    public static Optional<Class<?>> resolveTypeArgument(Class<?> forType, Class<?> genericSupertype, int typeArgumentIndex) {
        requireNonNull(forType, "No forType provided");
        requireNonNull(genericSupertype, "No genericSupertype provided");
        var typeParameters = genericSupertype.getTypeParameters();
        if (typeArgumentIndex < 0 || typeArgumentIndex >= typeParameters.length) {
            throw new IllegalArgumentException(msg("{} declares {} type parameter(s), cannot resolve the type argument with index {}",
                                                   genericSupertype.getName(),
                                                   typeParameters.length,
                                                   typeArgumentIndex));
        }
        return findTypeArgument(forType, genericSupertype, typeArgumentIndex, Map.of(), new HashSet<>());
    }

    private static Optional<Class<?>> findTypeArgument(Class<?> type,
                                                       Class<?> genericSupertype,
                                                       int typeArgumentIndex,
                                                       Map<TypeVariable<?>, Type> bindings,
                                                       Set<Class<?>> typesOnThePath) {
        if (type == null || type == Object.class || !typesOnThePath.add(type)) {
            return Optional.empty();
        }
        try {
            var supertypes = new ArrayList<Type>(Arrays.asList(type.getGenericInterfaces()));
            if (type.getGenericSuperclass() != null) {
                supertypes.add(type.getGenericSuperclass());
            }
            for (var supertype : supertypes) {
                Optional<Class<?>> found = Optional.empty();
                if (supertype instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawType) {
                    var arguments = parameterized.getActualTypeArguments();
                    if (rawType == genericSupertype) {
                        found = rawClassOf(bound(arguments[typeArgumentIndex], bindings));
                    } else {
                        var rawTypeParameters = rawType.getTypeParameters();
                        var nextBindings      = new HashMap<TypeVariable<?>, Type>();
                        for (int i = 0; i < rawTypeParameters.length && i < arguments.length; i++) {
                            nextBindings.put(rawTypeParameters[i], bound(arguments[i], bindings));
                        }
                        found = findTypeArgument(rawType, genericSupertype, typeArgumentIndex, nextBindings, typesOnThePath);
                    }
                } else if (supertype instanceof Class<?> rawType && rawType != genericSupertype) {
                    // A raw (non-parameterized) supertype passes no type arguments on
                    found = findTypeArgument(rawType, genericSupertype, typeArgumentIndex, Map.of(), typesOnThePath);
                }
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        } finally {
            typesOnThePath.remove(type);
        }
    }

    private static Type bound(Type argument, Map<TypeVariable<?>, Type> bindings) {
        return argument instanceof TypeVariable<?> variable && bindings.containsKey(variable) ? bindings.get(variable) : argument;
    }

    private static Optional<Class<?>> rawClassOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return Optional.of(clazz);
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawType) {
            return Optional.of(rawType);
        }
        return Optional.empty();
    }
}
