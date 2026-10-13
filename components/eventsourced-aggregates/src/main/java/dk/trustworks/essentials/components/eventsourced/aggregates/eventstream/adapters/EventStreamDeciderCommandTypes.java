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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;

import java.lang.reflect.*;
import java.util.*;

/**
 * <b>Internal - not part of the public API</b>, and may change in any release: public only so
 * {@link dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType},
 * in the parent package, resolves a decider's command type the same way as this package does.
 * <p>
 * Resolves the command types an {@link EventStreamDecider} declares through its {@code COMMAND} type argument - for
 * {@link EventStreamDeciderCommandHandlerAdapter#handledCommandTypes()}, the startup checks of
 * {@link EventStreamDeciderAndAggregateTypeConfigurator} and the aggregate type routing of
 * {@link dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType}
 */
public final class EventStreamDeciderCommandTypes {
    private EventStreamDeciderCommandTypes() {
    }

    /**
     * The command types checked are those a decider declares as its {@code COMMAND} type argument
     * ({@code implements EventStreamDecider<CreateOrder, OrderEvent>}), or - for a sealed one - its concrete permitted
     * subtypes, as far as the decider {@link EventStreamDecider#canHandle(Class) can handle} them.
     *
     * @param decider the decider
     * @return the concrete command types the decider declares and can handle; empty when its {@code COMMAND} type
     * argument cannot be resolved (a raw {@link EventStreamDecider}, a JDK dynamic proxy, or a type variable no subclass binds)
     */
    static Set<Class<?>> declaredCommandTypes(EventStreamDecider<?, ?> decider) {
        var commandTypes = new LinkedHashSet<Class<?>>();
        resolveCommandTypeArgument(decider.getClass()).ifPresent(declared -> collectConcreteTypes(declared, commandTypes, new HashSet<>()));
        commandTypes.removeIf(commandType -> !decider.canHandle(commandType));
        return commandTypes;
    }

    /**
     * @param deciderType the decider's class
     * @return the raw {@code COMMAND} type argument the decider class gives {@link EventStreamDecider}, found through its
     * superclasses and (sub-)interfaces, with type variables mapped back through each parameterization on the way
     * ({@code interface OrderDecider extends EventStreamDecider<OrderCommand, OrderEvent>}, {@code abstract class Base<C>
     * implements EventStreamDecider<C, E>}); empty when it stays a type variable or the class does not implement
     * {@link EventStreamDecider} with type arguments
     */
    public static Optional<Class<?>> resolveCommandTypeArgument(Class<?> deciderType) {
        return findCommandTypeArgument(deciderType, Map.of(), new HashSet<>())
                .map(commandTypeArgument -> {
                    if (commandTypeArgument instanceof Class<?> commandType) {
                        return commandType;
                    }
                    if (commandTypeArgument instanceof ParameterizedType parameterizedCommandType && parameterizedCommandType.getRawType() instanceof Class<?> commandType) {
                        return commandType;
                    }
                    return null;
                });
    }

    private static Optional<Type> findCommandTypeArgument(Class<?> type, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> visited) {
        if (type == null || type == Object.class || !visited.add(type)) {
            return Optional.empty();
        }
        var supertypes = new ArrayList<Type>(Arrays.asList(type.getGenericInterfaces()));
        if (type.getGenericSuperclass() != null) {
            supertypes.add(type.getGenericSuperclass());
        }
        for (var supertype : supertypes) {
            if (supertype instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawType) {
                var arguments = parameterized.getActualTypeArguments();
                if (rawType == EventStreamDecider.class) {
                    var argument = arguments[0];
                    while (argument instanceof TypeVariable<?> variable && bindings.containsKey(variable)) {
                        argument = bindings.get(variable);
                    }
                    return Optional.of(argument);
                }
                var typeParameters = rawType.getTypeParameters();
                var nextBindings = new HashMap<TypeVariable<?>, Type>();
                for (int i = 0; i < typeParameters.length && i < arguments.length; i++) {
                    var argument = arguments[i];
                    nextBindings.put(typeParameters[i], argument instanceof TypeVariable<?> variable && bindings.containsKey(variable) ? bindings.get(variable) : argument);
                }
                var found = findCommandTypeArgument(rawType, nextBindings, visited);
                if (found.isPresent()) {
                    return found;
                }
            } else if (supertype instanceof Class<?> rawType && rawType != EventStreamDecider.class) {
                var found = findCommandTypeArgument(rawType, Map.of(), visited);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Adds <code>type</code>, when concrete, and - for a sealed type - its permitted subtypes, recursively
     *
     * @param type          the type to walk
     * @param concreteTypes collects the concrete types found
     * @param visited       the types already walked
     */
    static void collectConcreteTypes(Class<?> type, Set<Class<?>> concreteTypes, Set<Class<?>> visited) {
        if (type == Object.class || !visited.add(type)) {
            return;
        }
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            concreteTypes.add(type);
        }
        if (type.isSealed()) {
            for (var permittedSubclass : type.getPermittedSubclasses()) {
                collectConcreteTypes(permittedSubclass, concreteTypes, visited);
            }
        }
    }
}
