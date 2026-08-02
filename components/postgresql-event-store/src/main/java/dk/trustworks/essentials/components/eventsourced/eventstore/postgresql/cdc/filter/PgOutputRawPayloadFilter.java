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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Tailer-side pre-filter for {@code pgoutput} raw WAL payloads. Applied in
 * {@code WalReplicationTailer.handleStreamMessage} when the plugin's
 * {@code preFiltersRawPayloads()} returns {@code true}, so that WAL messages irrelevant to
 * the configured aggregate event streams never make it into the CDC inbox in the first
 * place.
 * <p>
 * Why this exists: pgoutput with a {@code FOR ALL TABLES} publication (or any publication
 * broader than the event-stream tables) emits row-change wire messages for every table's
 * writes — including chatty framework tables like the durable-queues, fenced-lock,
 * subscription-tracking, and TTL tables. Previously every one of those was persisted to the
 * inbox and later decoded to an empty event list by the dispatcher. At 2000+ msgs/sec
 * sustained, that's a lot of wasted I/O, storage, and CPU.
 * <p>
 * The filter is stateful: it maintains a lightweight relation-id → table-name cache
 * populated from {@code 'R'} (Relation) messages as they stream past. Subsequent
 * {@code 'I'} (Insert) messages look up their table by relationId and are persisted only
 * when the table belongs to the configured event streams.
 * <p>
 * What each message type does:
 * <ul>
 *   <li>{@code 'R'} — parse to update relation cache; {@code persist=true} so the dispatcher's
 *       own decoder state cache is also populated when the inbox row is later processed.</li>
 *   <li>{@code 'I'} — peek the relationId (4 bytes after the type marker), look up the
 *       table name, and {@code persist=true} iff the table is in the configured event-stream
 *       set. Unknown relation (cache miss) conservatively returns {@code true} — the
 *       dispatcher will log and skip; we prefer wasted inbox bytes over event loss.</li>
 *   <li>{@code 'B'} / {@code 'C'} — {@code persist=false}. The dispatcher never reads the
 *       transactional fields off {@code PgOutputRowChange}; skipping them is the single
 *       biggest inbox-write saving (one B and one C per transaction regardless of whether
 *       any tracked rows changed).</li>
 *   <li>{@code 'U'} / {@code 'D'} / {@code 'T'} — Update / Delete / Truncate: event stores
 *       are append-only so these never produce events. {@code persist=false}.</li>
 *   <li>{@code 'Y'} (Type), {@code 'O'} (Origin), {@code 'M'} (Message), streaming markers
 *       {@code 'S'}/{@code 'E'}/{@code 'c'}/{@code 'A'} — no row-change content,
 *       {@code persist=false}.</li>
 * </ul>
 */
public final class PgOutputRawPayloadFilter implements WalMessageFilter {

    /**
     * Relation-id → fully-qualified table name ({@code schema.table}). Populated lazily as
     * {@code 'R'} messages stream past. Cleared externally only via
     * {@link #clearRelationCache()} — primarily for tests and for driving future cache-
     * invalidation semantics (e.g. after slot recreation).
     */
    private final Map<Integer, String>       relationIdToTable = new ConcurrentHashMap<>();
    /**
     * Live supplier of the configured event-stream table names — mirrors the supplier used by
     * {@code DefaultWalMessageFilter} and {@code DefaultAggregateTypeResolver}. Same live-view
     * semantics: runtime aggregate registration becomes visible to this filter on the next
     * {@link #shouldPersist(byte[])} call, no rewiring needed.
     */
    private final Supplier<Collection<String>> aggregateEventStreamTableNamesSupplier;

    public PgOutputRawPayloadFilter(Supplier<Collection<String>> aggregateEventStreamTableNamesSupplier) {
        this.aggregateEventStreamTableNamesSupplier = requireNonNull(
                aggregateEventStreamTableNamesSupplier,
                "aggregateEventStreamTableNamesSupplier cannot be null");
    }

    /**
     * pgoutput is a binary protocol — the string-based filter path isn't applicable. Return
     * {@code false} so the tailer's {@code preFilter} treats any stray text payload as
     * something to drop. In practice the tailer never calls this form for pgoutput, but the
     * interface exposes both variants and we'd rather be defensively conservative than let
     * an unexpected text payload slip through into the inbox.
     */
    @Override
    public boolean shouldPersist(String walJson) {
        return false;
    }

    @Override
    public boolean shouldPersist(byte[] walPayloadBytes) {
        if (walPayloadBytes == null || walPayloadBytes.length == 0) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(walPayloadBytes);
        char type = (char) buffer.get();
        return switch (type) {
            case 'R' -> {
                // Parse the relation and update the cache so later 'I' messages can look up
                // the table name. Returns true because the dispatcher's own decoder will
                // re-parse this 'R' message from the inbox to populate its own cache — we
                // must not drop Relation messages at the tailer or the dispatcher decoder
                // breaks when it later encounters an 'I' for this relation.
                updateRelationCache(buffer);
                yield true;
            }
            case 'I' -> {
                // An Insert message is: 'I' + int32 relationId + byte tuple-kind + tuple data.
                // Peek only the relationId (cheap — 4 bytes), skip the rest until decision.
                if (buffer.remaining() < 4) yield true; // malformed — let dispatcher handle
                int relationId = buffer.getInt();
                String tableName = relationIdToTable.get(relationId);
                if (tableName == null) {
                    // Cache miss — conservatively persist. A well-formed pgoutput stream
                    // always sends 'R' before 'I' for a given relation, but we'd rather
                    // keep an unexpected row than silently drop it.
                    yield true;
                }
                yield isTrackedEventTable(tableName);
            }
            // All the below produce no event-store events. Dropping them at the tailer is the
            // whole point of this filter: the inbox previously had to absorb them all and the
            // dispatcher decoded them into empty event lists.
            default -> false;
        };
    }

    /**
     * Parse an already-stripped-of-type-byte {@code 'R'} message and update the relation
     * cache. Format: int32 relationId, cstring namespace, cstring relationName, then more
     * fields we don't need here. Walks the buffer only as far as the relation name.
     */
    private void updateRelationCache(ByteBuffer buffer) {
        if (buffer.remaining() < 4) return;
        int relationId = buffer.getInt();
        String namespace = readCString(buffer);
        String relationName = readCString(buffer);
        if (relationName == null || relationName.isEmpty()) return;
        String fq = (namespace == null || namespace.isBlank())
                    ? relationName
                    : namespace + "." + relationName;
        relationIdToTable.put(relationId, fq.toLowerCase(Locale.ROOT));
    }

    /**
     * Read a NUL-terminated UTF-8 string out of the buffer, advancing past the terminator.
     * Returns {@code null} when the buffer ends without finding a NUL (malformed payload —
     * caller treats this as "skip").
     */
    private static String readCString(ByteBuffer buffer) {
        int start = buffer.position();
        while (buffer.hasRemaining()) {
            if (buffer.get() == 0) {
                int end = buffer.position() - 1;
                int length = end - start;
                byte[] bytes = new byte[length];
                int saved = buffer.position();
                buffer.position(start);
                buffer.get(bytes);
                buffer.position(saved);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Loose match between a cached qualified table name ({@code schema.table}) and the set
     * of registered event-stream table names (which may be qualified or bare). A bare
     * registered name matches any qualified cache entry with the same table portion.
     */
    private boolean isTrackedEventTable(String qualifiedTableName) {
        Collection<String> registered = aggregateEventStreamTableNamesSupplier.get();
        if (registered == null || registered.isEmpty()) return false;
        String cacheBare = stripSchema(qualifiedTableName);
        for (String entry : registered) {
            if (entry == null || entry.isBlank()) continue;
            String normalizedEntry = entry.toLowerCase(Locale.ROOT);
            if (qualifiedTableName.equals(normalizedEntry)) return true;
            if (cacheBare.equals(stripSchema(normalizedEntry))) return true;
        }
        return false;
    }

    private static String stripSchema(String maybeQualified) {
        int dot = maybeQualified.indexOf('.');
        return dot < 0 ? maybeQualified : maybeQualified.substring(dot + 1);
    }

    /**
     * Clears the relation-id cache. Intended for tests and for the slot-recreation path
     * (after a fresh slot, the server may assign different relation OIDs for the same
     * tables, so any carry-over entries would be stale).
     */
    public void clearRelationCache() {
        relationIdToTable.clear();
    }

    /**
     * Read-only view of the current cache. Primarily for tests / diagnostics.
     */
    public Map<Integer, String> getRelationIdToTableSnapshot() {
        return Map.copyOf(relationIdToTable);
    }

    // Small helpers used exclusively by tests to avoid depending on the full ConcurrentMap
    // API surface.
    static Set<String> toLowerSet(Collection<String> values) {
        if (values == null) return Set.of();
        return values.stream()
                     .filter(Objects::nonNull)
                     .map(v -> v.toLowerCase(Locale.ROOT))
                     .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
