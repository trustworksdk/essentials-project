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
package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.EventTypeFilter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventType;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.shared.reflection.Methods;
import org.slf4j.*;

import java.lang.reflect.Modifier;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Derives the {@link EventTypeFilter} a processor base class subscribes with from the payload parameter types of its
 * {@link MessageHandler} methods, so the engine skips the other event types in SQL instead of delivering them to be
 * ignored.
 * <p>
 * A stored event type is matched by exact name ({@code FQCN:} + the class name, {@link EventType#of(Class)}), while
 * dispatch picks the most specific handler, so a handler on a supertype or interface also receives subclasses. A name
 * filter is therefore only derived when every handled payload type is a final class (a record, an enum without constant
 * bodies, a final class) outside the JDK: no other class can be delivered to such a handler. Otherwise - a non-final
 * class, an interface, an abstract class, {@code Object}, {@code String} (an event stored by name), an array or a
 * primitive - the filter is {@link EventTypeFilter#all()}, as is a processor without handlers.
 * <p>
 * Runs once, when a processor builds its subscriptions; nothing here is on the delivery path.
 */
final class HandledEventTypes {
    private static final Logger log = LoggerFactory.getLogger(HandledEventTypes.class);

    private HandledEventTypes() {
    }

    /**
     * @param processorName  the processor's name, for the log line
     * @param handlerClass   the class whose {@link MessageHandler} methods (declared or inherited) handle the events
     * @return a filter of the handled payload types' event type names, or {@link EventTypeFilter#all()} when a handled
     * type could also receive other classes
     */
    static EventTypeFilter derive(String processorName, Class<?> handlerClass) {
        requireNonNull(handlerClass, "No handlerClass provided");
        var payloadTypes = new TreeSet<Class<?>>(Comparator.comparing(Class::getName));
        for (var method : Methods.methods(handlerClass)) {
            if (method.getDeclaringClass() != Object.class && method.isAnnotationPresent(MessageHandler.class) && method.getParameterCount() >= 1) {
                payloadTypes.add(method.getParameterTypes()[0]);
            }
        }
        return derive(processorName, payloadTypes);
    }

    /** As {@link #derive(String, Class)}, from the handled payload types themselves */
    static EventTypeFilter derive(String processorName, Collection<Class<?>> payloadTypes) {
        requireNonNull(payloadTypes, "No payloadTypes provided");
        if (payloadTypes.isEmpty()) {
            log.info("[{}] Subscribing to every event type: no @MessageHandler methods", processorName);
            return EventTypeFilter.all();
        }
        var names = new TreeSet<String>();
        for (var type : payloadTypes) {
            var reason = whyNotFilterable(type);
            if (reason.isPresent()) {
                log.info("[{}] Subscribing to every event type: the @MessageHandler payload type {} {}, so a name filter could drop events it handles",
                         processorName, type.getName(), reason.get());
                return EventTypeFilter.all();
            }
            names.add(EventType.of(type).toString());
        }
        log.info("[{}] Subscribing only to the {} event type(s) its @MessageHandler methods handle: {}", processorName, names.size(), names);
        return new EventTypeFilter(names);
    }

    /** @return why events of other classes could reach a handler of {@code type}; empty when only {@code type} itself can */
    static Optional<String> whyNotFilterable(Class<?> type) {
        if (type.isPrimitive() || type.isArray()) {
            return Optional.of("is not a class");
        }
        if (type.isInterface()) {
            return Optional.of("is an interface");
        }
        if (!Modifier.isFinal(type.getModifiers())) {
            return Optional.of("is not final");
        }
        var packageName = type.getPackageName();
        if (packageName.startsWith("java.") || packageName.startsWith("javax.")) {
            return Optional.of("is a JDK type (an event stored by name is delivered as its JSON text)");
        }
        return Optional.empty();
    }
}
