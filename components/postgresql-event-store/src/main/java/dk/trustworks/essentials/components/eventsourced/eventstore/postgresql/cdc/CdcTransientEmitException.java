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
 * Base type for <b>transient</b> CDC bus emit failures raised by {@link CdcSinkEmitter}: the event
 * itself is fine and the sink will eventually accept it, so the {@code CdcDispatcher} treats these as
 * retry-later conditions — the inbox row stays in {@code RECEIVED} status for the next tick rather than
 * being quarantined as POISON.
 * <p>
 * Distinguishing these from a genuine conversion failure (malformed payload, unknown relation, missing
 * columns, …) is the whole point: a conversion failure poisons the row and registers a permanent gap,
 * which permanently drops live-tail delivery of that global order. A transient emit failure must not.
 * <p>
 * Concrete subtypes:
 * <ul>
 *     <li>{@link CdcBusOverflowException} — {@code FAIL_OVERFLOW}: subscribers are behind producers.</li>
 *     <li>{@link CdcNonSerializedEmitException} — {@code FAIL_NON_SERIALIZED}: a concurrent-emission
 *         race lost the serialized-access window.</li>
 * </ul>
 * Extends {@link IllegalStateException} to stay source-compatible with pre-existing
 * {@code catch (IllegalStateException)} / {@code catch (Exception)} sites.
 */
public class CdcTransientEmitException extends IllegalStateException {
    public CdcTransientEmitException(String message) {
        super(message);
    }
}
