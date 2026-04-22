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

/**
 * Thrown by {@link CdcSinkEmitter} when {@code Sinks.Many#tryEmitNext} returns
 * {@code FAIL_OVERFLOW} and the configured retry budget has been exhausted while the overflow
 * policy is {@link CdcProperties.CdcOverflowPolicy#FAIL_FAST}.
 * <p>
 * Semantically this is a <b>transient backpressure signal</b>: the event itself is fine and
 * the sink will eventually accept it once downstream subscribers catch up. The
 * {@code CdcDispatcher} distinguishes this from a genuine conversion failure (malformed
 * payload, unknown relation, missing columns, …) and treats it as a retry-later condition —
 * the inbox row stays in {@code RECEIVED} status so the next dispatcher tick can try again.
 * Previously this exception was caught as a generic conversion failure and the row was
 * poisoned, permanently losing live-tail delivery for that event even though polling fallback
 * could still catch it via the event store.
 * <p>
 * Extending {@link IllegalStateException} (rather than {@link RuntimeException} directly) keeps
 * source-compatibility with existing {@code catch (IllegalStateException)} / {@code catch (Exception)}
 * sites that pre-date the distinction; we just need the type-narrow catch in the dispatcher to
 * intercept it before the generic handler.
 */
public class CdcBusOverflowException extends IllegalStateException {
    public CdcBusOverflowException(String message) {
        super(message);
    }
}
