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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter.WalMessageFilter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import org.jdbi.v3.core.Handle;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
     * Hook called by the tailer once per {@code start()}, after successfully acquiring a
     * replication connection but before {@code START_REPLICATION} is issued. Plugins that need
     * to prepare server-side state (e.g. pgoutput auto-managing its publication) implement this
     * to run their bootstrapping SQL. The {@code eventStreamTableNames} supplier returns the
     * current set of registered event-stream table names — plugins can use it to decide
     * publication membership, build ALTER statements, etc.
     * <p>
     * Default implementation is a no-op. Implementations should <b>never</b> throw for
     * recoverable / optional work (e.g. missing privileges for publication auto-manage); log a
     * loud WARN and return instead, so the tailer falls back to streaming whatever the
     * server-side state already provides.
     */
    default void prepare(Handle handle, Supplier<Set<String>> eventStreamTableNames) {
        // no-op by default
    }

    /**
     * The value the tailer stores in the CDC inbox's {@code lsn} column, which doubles as the
     * {@code unique(slot_name, lsn)} dedup key.
     * <p>
     * The default is the raw LSN reported by {@code PGReplicationStream#getLastReceiveLSN()},
     * which is correct for any plugin whose messages each sit at their own WAL position. It is
     * <b>not</b> correct for pgoutput: over the streaming protocol every RELATION message is
     * reported at {@code 0/0}, so a raw-LSN key collapses the schema announcements for <em>all</em>
     * tables onto one row and every table but the first loses its schema — see
     * {@code PgOutputLogicalDecodingPlugin#inboxDedupKey}.
     * <p>
     * Implementations must return a <b>deterministic</b> key: the same WAL message re-streamed
     * after a reconnect has to produce the same key, or the inbox's replay-idempotency guarantee
     * is lost and the message is dispatched twice.
     *
     * @param payloadBytes the raw WAL payload about to be persisted
     * @param lsn          the LSN the replication stream reported for this message
     * @return the dedup key to store; never {@code null}
     */
    default String inboxDedupKey(byte[] payloadBytes, String lsn) {
        return lsn;
    }

    /**
     * Leading payload bytes that mark a message as carrying <em>schema</em> rather than row data —
     * metadata the decoder must have cached before any row payload referencing it can be decoded.
     * <p>
     * The dispatcher uses this for two things: priming its decoder from the inbox at start-up (so a
     * restart doesn't leave the in-memory schema cache empty while row payloads are still pending),
     * and exempting schema rows from {@link CdcProperties.DispatchedRowPolicy#DELETE} so the priming
     * source survives.
     * <p>
     * Returning an empty set (the default) opts out of both — correct for text formats such as
     * {@code wal2json}, whose payloads are self-describing.
     */
    default Set<Integer> schemaPayloadLeadingBytes() {
        return Set.of();
    }

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

    /**
     * Plugin-supplied default raw-payload {@link WalMessageFilter} for use by the tailer when
     * the caller hasn't provided one explicitly. Each plugin knows the exact filter shape its
     * payloads need:
     * <ul>
     *   <li>{@code wal2json} → {@code DefaultWalMessageFilter} (Jackson-driven, table-name aware).</li>
     *   <li>{@code pgoutput} → {@code PgOutputRawPayloadFilter} (binary-header peek, table-name aware).</li>
     * </ul>
     * Returning {@link Optional#empty()} (the default) means the plugin has no opinion and the
     * tailer will fall back to a generic last-resort filter — that path is intentionally unwise:
     * a plugin that opts into raw-payload pre-filtering ({@link #preFiltersRawPayloads()} =
     * {@code true}) but doesn't supply a default risks dropping every message under the generic
     * fallback. Override this method whenever {@code preFiltersRawPayloads()} is {@code true}.
     *
     * @param eventStreamTableNamesSupplier live supplier of registered event-stream table names.
     *                                      Filters that need to know "is this WAL message for a
     *                                      table I care about?" wire it through here so runtime
     *                                      aggregate registration is observed without rebuilding
     *                                      the filter.
     */
    default Optional<WalMessageFilter> defaultRawPayloadFilter(Supplier<Set<String>> eventStreamTableNamesSupplier) {
        return Optional.empty();
    }

    /**
     * Plugin-specific diagnostic counters surfaced in {@code CdcDispatcherStatus} so the
     * effectiveness monitor's failure log can explain why zero events were published. Default
     * implementation returns {@link DiagnosticSummary#EMPTY} — plugins that silently drop rows
     * (e.g. pgoutput dropping INSERTs with unknown aggregates) should override to expose those
     * counts.
     */
    default DiagnosticSummary diagnosticSummary() {
        return DiagnosticSummary.EMPTY;
    }

    /**
     * Immutable snapshot of plugin-specific decode outcomes. Fields that don't apply to a given
     * plugin stay at {@code -1} (unknown) so callers rendering the summary can skip them.
     * <p>
     * {@code insertsSeen} is the total number of INSERT row-changes (or equivalent) the plugin
     * has been asked to convert; {@code insertsDroppedUnknownAggregate} is how many of those
     * were rejected because the table didn't resolve to a registered aggregate. When the two
     * are equal and non-zero while the dispatcher's {@code publishedEvents} is zero, the
     * aggregate-type-resolver is the smoking gun.
     * <p>
     * {@code extra} is plugin-specific free-form diagnostic text (e.g. pgoutput message-type
     * histograms) rendered into the monitor failure log as-is. May be {@code null} or blank
     * when the plugin has nothing to add.
     */
    record DiagnosticSummary(long insertsSeen,
                             long insertsDroppedUnknownAggregate,
                             String extra) {
        public static final DiagnosticSummary EMPTY = new DiagnosticSummary(-1L, -1L, null);
    }
}
