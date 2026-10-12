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
 * Resolves the command types an {@link EventStreamDecider} declares through its {@code COMMAND} type argument - for
 * {@link EventStreamDeciderCommandHandlerAdapter#handledCommandTypes()} and the startup checks of
 * {@link EventStreamDeciderAndAggregateTypeConfigurator}
 */
final class EventStreamDeciderCommandTypes {
    private EventStreamDeciderCommandTypes() {
    }

    /**
     * The command types checked are those a decider declares as its {@code COMMAND} type argument
     * ({@code implements EventStreamDecider<CreateOrder, OrderEvent>}), or - for a sealed one - its concrete permitted
     * subtypes, as far as the decider {@link EventStreamDecider#canHandle(Class) can handle} them.
     *
     * @param decider the decider
     * @return the concrete command types the decider declares and can handle; empty when its {@code COMMAND} type
     * argument cannot be resolved (a lambda, a generic base class)
     */
    static Set<Class<?>> declaredCommandTypes(EventStreamDecider<?, ?> decider) {
        var commandTypes = new LinkedHashSet<Class<?>>();
        resolveCommandTypeArgument(decider.getClass()).ifPresent(declared -> collectConcreteTypes(declared, commandTypes, new HashSet<>()));
        commandTypes.removeIf(commandType -> !decider.canHandle(commandType));
        return commandTypes;
    }

    /**
     * @param deciderType the decider's class
     * @return the raw {@code COMMAND} type argument the decider class (or a superclass) gives {@link EventStreamDecider};
     * empty when it is a type variable or the class does not implement {@link EventStreamDecider} with type arguments
     */
    static Optional<Class<?>> resolveCommandTypeArgument(Class<?> deciderType) {
        for (Class<?> type = deciderType; type != null && type != Object.class; type = type.getSuperclass()) {
            for (var genericInterface : type.getGenericInterfaces()) {
                if (genericInterface instanceof ParameterizedType parameterized && parameterized.getRawType() == EventStreamDecider.class) {
                    var commandTypeArgument = parameterized.getActualTypeArguments()[0];
                    if (commandTypeArgument instanceof Class<?> commandType) {
                        return Optional.of(commandType);
                    }
                    if (commandTypeArgument instanceof ParameterizedType parameterizedCommandType && parameterizedCommandType.getRawType() instanceof Class<?> commandType) {
                        return Optional.of(commandType);
                    }
                    return Optional.empty();
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
