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
import dk.trustworks.essentials.shared.types.GenericType;

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
 *
 * @deprecated internal; it was never meant to be called by an application. Use
 * {@link GenericType#resolveTypeArgument(Class, Class, int)} instead; this class becomes package-private in a later
 * minor release
 */
@Deprecated(forRemoval = true, since = "0.60.1")
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
        return GenericType.resolveTypeArgument(deciderType, EventStreamDecider.class, 0);
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
