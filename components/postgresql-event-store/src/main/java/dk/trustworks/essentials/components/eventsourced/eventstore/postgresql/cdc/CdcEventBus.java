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
import java.util.concurrent.locks.LockSupport;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcOverflowPolicy;
import static dk.trustworks.essentials.shared.FailFast.*;

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

    private final int backpressureBufferSize;
    private final int nonSerializedMaxRetries;
    private final int overflowMaxRetries;
    private final CdcOverflowPolicy overflowPolicy;
    private final ConcurrentMap<AggregateType, Sinks.Many<PersistedEvent>> sinks = new ConcurrentHashMap<>();

    public CdcEventBus() {
        this(new CdcProperties.CdcEventBusProperties());
    }

    public CdcEventBus(CdcProperties.CdcEventBusProperties properties) {
        requireNonNull(properties, "properties is required");
        requireTrue(properties.getBackpressureBufferSize() > 0, "backpressureBufferSize must be > 0");
        requireTrue(properties.getNonSerializedMaxRetries() > 0, "nonSerializedMaxRetries must be > 0");
        requireTrue(properties.getOverflowMaxRetries() >= 0, "overflowMaxRetries must be >= 0");

        this.backpressureBufferSize = properties.getBackpressureBufferSize();
        this.nonSerializedMaxRetries = properties.getNonSerializedMaxRetries();
        this.overflowMaxRetries = properties.getOverflowMaxRetries();
        this.overflowPolicy = requireNonNull(properties.getOverflowPolicy(), "overflowPolicy is required");
    }

    public void publish(List<PersistedEvent> events) {
        if (log.isTraceEnabled()) {
            log.trace("Publishing '{}' persisted events", events.size());
        }
        for (var e : events) {
            emitOrFail(e);
        }
    }

    private void emitOrFail(PersistedEvent event) {
        var sink = sink(event.aggregateType());

        int overflowAttempt = 0;
        for (int attempt = 1; attempt <= nonSerializedMaxRetries; attempt++) {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result == Sinks.EmitResult.OK) {
                return;
            }
            if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
                if (attempt < nonSerializedMaxRetries) {
                    Thread.onSpinWait();
                    continue;
                }
                handleFailure(event, result, "CDC bus non-serialized emit retries exhausted");
                return;
            }
            if (result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                log.debug("Dropping CDC event with globalOrder={} because no CDC subscriber is active for aggregate '{}'",
                          event.globalEventOrder(),
                          event.aggregateType());
                return;
            }
            if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                if (overflowAttempt < overflowMaxRetries) {
                    overflowAttempt++;
                    long delayMs = Math.min(1L << Math.min(overflowAttempt - 1, 8), 250L);
                    LockSupport.parkNanos(delayMs * 1_000_000L);
                    continue;
                }
                handleFailure(event, result, "CDC bus overflow retries exhausted");
                return;
            }
            if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED) {
                handleFailure(event, result, "CDC bus sink unavailable");
                return;
            }
            // Any future EmitResult values should fail closed rather than silently drop.
            handleFailure(event, result, "CDC bus emit failed");
            return;
        }
    }

    private void handleFailure(PersistedEvent event, Sinks.EmitResult result, String message) {
        String fullMessage = message + " (emitResult=" + result + ", globalOrder=" + event.globalEventOrder() + ", aggregateType=" + event.aggregateType() + ")";
        if (overflowPolicy == CdcOverflowPolicy.LOG_AND_DROP) {
            log.warn(fullMessage);
            return;
        }
        throw new IllegalStateException(fullMessage);
    }

    public Flux<PersistedEvent> fluxForAggregate(AggregateType aggregateType) {
        return sink(aggregateType).asFlux();
    }

    private Sinks.Many<PersistedEvent> sink(AggregateType aggregateType) {
        return sinks.computeIfAbsent(aggregateType, at ->
                                             // multicast for many subscribers; buffer for slow consumers
                                             Sinks.many().multicast().onBackpressureBuffer(backpressureBufferSize, false)
                                    );
    }

}
