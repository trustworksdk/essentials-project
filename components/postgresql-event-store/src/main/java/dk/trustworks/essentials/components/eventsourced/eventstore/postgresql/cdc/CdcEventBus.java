/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import org.slf4j.*;
import reactor.core.publisher.*;

import java.util.List;
import java.util.concurrent.*;

/**
 * The CdcEventBus class is responsible for publishing and managing persisted events
 * categorized by their corresponding aggregate type. It facilitates event propagation
 * to multiple subscribers in a reactive and thread-safe manner.
 * <p>
 * The class supports:
 * - Publishing lists of persisted events based on their aggregate type.
 * - Providing a reactive stream (Flux) for subscribers interested in a specific
 *   aggregate type.
 * <p>
 * It utilizes Reactor's {@link Sinks.Many} to maintain event streams for each aggregate type
 * and handles backpressure by buffering events for slow consumers.
 */
public class CdcEventBus {

    private static final Logger log = LoggerFactory.getLogger(CdcEventBus.class);

    private final ConcurrentMap<AggregateType, Sinks.Many<PersistedEvent>> sinks = new ConcurrentHashMap<>();

    public void publish(List<PersistedEvent> events) {
        if (log.isTraceEnabled()) {
            log.trace("Publishing '{}' persisted events", events.size());
        }
        for (var e : events) {
            sink(e.aggregateType()).tryEmitNext(e);
        }
    }

    public Flux<PersistedEvent> fluxForAggregate(AggregateType aggregateType) {
        return sink(aggregateType).asFlux();
    }

    private Sinks.Many<PersistedEvent> sink(AggregateType aggregateType) {
        return sinks.computeIfAbsent(aggregateType, at ->
                                             // multicast for many subscribers; buffer for slow consumers
                                             Sinks.many().multicast().onBackpressureBuffer()
                                    );
    }

}
