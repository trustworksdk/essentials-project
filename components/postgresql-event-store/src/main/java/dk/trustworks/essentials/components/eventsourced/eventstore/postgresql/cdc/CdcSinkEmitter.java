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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import org.slf4j.Logger;
import reactor.core.publisher.Sinks;

import java.util.concurrent.locks.LockSupport;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcOverflowPolicy;

/**
 * Shared emit-with-retry-and-policy helper for CDC {@link Sinks.Many} sinks.
 * <p>
 * Handles the four failure modes of {@link Sinks.Many#tryEmitNext}:
 * <ul>
 *     <li>{@code FAIL_NON_SERIALIZED} — spin-retry up to {@code nonSerializedMaxRetries}.</li>
 *     <li>{@code FAIL_OVERFLOW} — exponential-backoff park up to {@code overflowMaxRetries}.</li>
 *     <li>{@code FAIL_ZERO_SUBSCRIBER} — debug log and drop.</li>
 *     <li>{@code FAIL_TERMINATED} / {@code FAIL_CANCELLED} / other — apply {@link CdcOverflowPolicy}.</li>
 * </ul>
 * When retries are exhausted, the configured {@link CdcOverflowPolicy} decides between
 * {@link CdcOverflowPolicy#FAIL_FAST} (throw) and {@link CdcOverflowPolicy#LOG_AND_DROP} (warn).
 */
final class CdcSinkEmitter {

    private CdcSinkEmitter() {
    }

    static void tryEmit(Sinks.Many<PersistedEvent> sink,
                        PersistedEvent event,
                        int nonSerializedMaxRetries,
                        int overflowMaxRetries,
                        CdcOverflowPolicy overflowPolicy,
                        String context,
                        Logger log) {
        // Decoupled counters: an outer attempt cap would cause whichever retry budget is smaller
        // to short-circuit the other silently. Each failure mode owns its own budget.
        int nonSerializedAttempts = 0;
        int overflowAttempts      = 0;
        while (true) {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result == Sinks.EmitResult.OK) {
                return;
            }
            if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
                nonSerializedAttempts++;
                if (nonSerializedAttempts >= nonSerializedMaxRetries) {
                    handleFailure(event, result, overflowPolicy, context, "non-serialized emit retries exhausted", log);
                    return;
                }
                Thread.onSpinWait();
                continue;
            }
            if (result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                log.debug("[{}] Dropping CDC event globalOrder={} aggregateType={} — no active subscriber",
                          context, event.globalEventOrder(), event.aggregateType());
                return;
            }
            if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                if (overflowAttempts >= overflowMaxRetries) {
                    handleFailure(event, result, overflowPolicy, context, "overflow retries exhausted", log);
                    return;
                }
                overflowAttempts++;
                long delayMs = Math.min(1L << Math.min(overflowAttempts - 1, 8), 250L);
                LockSupport.parkNanos(delayMs * 1_000_000L);
                continue;
            }
            if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED) {
                handleFailure(event, result, overflowPolicy, context, "sink unavailable", log);
                return;
            }
            handleFailure(event, result, overflowPolicy, context, "emit failed", log);
            return;
        }
    }

    private static void handleFailure(PersistedEvent event,
                                      Sinks.EmitResult result,
                                      CdcOverflowPolicy overflowPolicy,
                                      String context,
                                      String reason,
                                      Logger log) {
        String fullMessage = "[" + context + "] " + reason
                + " (emitResult=" + result
                + ", globalOrder=" + event.globalEventOrder()
                + ", aggregateType=" + event.aggregateType() + ")";
        if (overflowPolicy == CdcOverflowPolicy.LOG_AND_DROP) {
            log.warn(fullMessage);
            return;
        }
        throw new IllegalStateException(fullMessage);
    }
}
