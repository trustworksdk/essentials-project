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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.WalGlobalOrdersExtractor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import org.jdbi.v3.core.Handle;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Describes a logical decoding plugin used by the CDC tailer.
 * <p>
 * The plugin owns the end-to-end contract for its WAL payload format: how the slot is
 * provisioned, whether the plugin is usable against the current DB, how to decode payloads
 * into {@link PersistedEvent}s, and how to best-effort extract global-order gap candidates
 * for poison handling. The tailer and dispatcher treat plugins as opaque — no plugin-specific
 * branching lives outside the plugin itself.
 */
public interface LogicalDecodingPlugin {
    String pluginName();

    Optional<String> unusableReason(Handle handle);

    default boolean isUsable(Handle handle) {
        return unusableReason(handle).isEmpty();
    }

    Map<String, Object> slotOptions();

    /**
     * Decode a single WAL replication payload into zero or more {@link PersistedEvent}s.
     * <p>
     * Returns an empty list if the payload is irrelevant (e.g. BEGIN/COMMIT messages, rows
     * on tables outside the configured aggregate event streams).
     */
    List<PersistedEvent> decode(byte[] payloadBytes);

    /**
     * Best-effort extraction of global-order gap candidates from a WAL payload, for poison
     * handling. When {@link #decode(byte[])} throws, the dispatcher calls this to register
     * permanent gaps so subscribers can advance past the unresolvable event.
     * <p>
     * Returns an empty list if no gaps can be extracted (e.g. the payload is not an insert
     * on a configured event table). Implementations must be tolerant of the same malformed
     * payloads that {@link #decode(byte[])} rejected — they are free to throw as well; the
     * dispatcher guards against that.
     */
    List<WalGlobalOrdersExtractor.Gap> extractGaps(byte[] payloadBytes);

    /**
     * Whether the tailer should apply the configured {@code WalMessageFilter} to raw payload
     * bytes before persisting to the inbox.
     * <p>
     * Text-based payloads (e.g. {@code wal2json}) support cheap byte-level regex filtering as
     * an optimisation. Binary protocols (e.g. {@code pgoutput}) cannot be safely regex-matched
     * at the byte level and should return {@code false} — filtering is instead the plugin's
     * responsibility during {@link #decode(byte[])}.
     */
    default boolean preFiltersRawPayloads() {
        return false;
    }
}
