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

package dk.trustworks.essentials.components.eventlog.integration;

import org.slf4j.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Lambda dispatch by payload type (design phase 8 §3.3): a {@code Map<Class<?>, handler>} resolved on the payload's
 * class, then its superclasses, then its interfaces, cached per class. No reflection on annotated methods.
 */
final class Dispatch<H> {
    private static final Logger log = LoggerFactory.getLogger(Dispatch.class);
    private static final Object NONE = new Object();

    private final Map<Class<?>, H>                  handlers;
    private final ConcurrentHashMap<Class<?>, Object> resolved = new ConcurrentHashMap<>();
    private final boolean                           strict;
    private final String                            owner;

    Dispatch(Map<Class<?>, H> handlers, boolean strict, String owner) {
        this.handlers = Map.copyOf(requireNonNull(handlers, "No handlers provided"));
        this.strict = strict;
        this.owner = owner;
    }

    boolean isEmpty() {
        return handlers.isEmpty();
    }

    /**
     * @return the handler for {@code payloadType}, or empty for an unknown type (logged once per type at DEBUG)
     * @throws IllegalStateException for an unknown type when strict
     */
    @SuppressWarnings("unchecked")
    Optional<H> handlerFor(Class<?> payloadType) {
        var handler = resolved.computeIfAbsent(payloadType, type -> {
            var found = find(type);
            if (found == null) {
                log.debug("[{}] No handler for payload type {}: events of it count as handled{}", owner, type.getName(), strict ? " (strict: failing)" : "");
            }
            return found == null ? NONE : found;
        });
        if (handler == NONE) {
            if (strict) {
                throw new IllegalStateException("[" + owner + "] No handler for payload type " + payloadType.getName() + " (strict)");
            }
            return Optional.empty();
        }
        return Optional.of((H) handler);
    }

    private H find(Class<?> type) {
        for (var current = type; current != null; current = current.getSuperclass()) {
            var handler = handlers.get(current);
            if (handler != null) {
                return handler;
            }
        }
        var queue = new ArrayDeque<Class<?>>();
        for (var current = type; current != null; current = current.getSuperclass()) {
            queue.addAll(List.of(current.getInterfaces()));
        }
        var seen = new HashSet<Class<?>>();
        while (!queue.isEmpty()) {
            var next = queue.poll();
            if (!seen.add(next)) {
                continue;
            }
            var handler = handlers.get(next);
            if (handler != null) {
                return handler;
            }
            queue.addAll(List.of(next.getInterfaces()));
        }
        return null;
    }
}
