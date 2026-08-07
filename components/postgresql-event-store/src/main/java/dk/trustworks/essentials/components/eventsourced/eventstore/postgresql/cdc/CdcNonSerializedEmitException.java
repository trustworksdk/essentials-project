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
 * {@code FAIL_NON_SERIALIZED} and the spin-retry budget has been exhausted while the overflow policy is
 * {@link CdcProperties.CdcOverflowPolicy#FAIL_FAST}.
 * <p>
 * {@code FAIL_NON_SERIALIZED} means a concurrent emitter held the sink's serialized-access window for
 * the whole retry budget — a <b>transient concurrency race</b>, not a problem with the event, which
 * decoded fine. As a {@link CdcTransientEmitException} the {@code CdcDispatcher} retries the inbox row
 * on the next tick instead of poisoning it (which would permanently drop live-tail delivery of that
 * global order).
 */
public class CdcNonSerializedEmitException extends CdcTransientEmitException {
    public CdcNonSerializedEmitException(String message) {
        super(message);
    }
}
