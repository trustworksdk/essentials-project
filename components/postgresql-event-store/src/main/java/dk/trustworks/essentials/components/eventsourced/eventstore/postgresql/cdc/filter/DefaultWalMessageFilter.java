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

import com.fasterxml.jackson.core.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Filters wal2json messages so we only persist INSERT changes for configured event stream tables.
 * <p>
 * The filter takes a {@link Supplier} of table names rather than a fixed collection so that
 * aggregates registered at runtime (e.g. via {@code addAggregateEventStreamConfiguration}) become
 * visible to filtering without needing to rebuild the Spring context. Earlier versions of this
 * class captured the table-name set at construction time — any runtime registration was silently
 * invisible to the filter, causing {@link #shouldPersist} to return {@code false} for the new
 * aggregate's events and dropping them before they reached the CDC inbox.
 * <p>
 * Each {@link #shouldPersist} call snapshots the supplier's result once at the top of the call and
 * reuses that snapshot for every {@code change} entry in the WAL message. The snapshot cost is
 * typically a few microseconds (one {@code Collectors.toMap} over a handful of configured
 * aggregates) versus milliseconds of JSON parsing, so the overhead is negligible at CDC dispatch
 * rates.
 */
public class DefaultWalMessageFilter implements WalMessageFilter {
    private final JsonFactory                    jsonFactory;
    private final Supplier<Collection<String>>   aggregateEventStreamTableNamesSupplier;

    /**
     * Primary constructor — takes a live supplier so aggregates registered at runtime are visible
     * to filtering. Typical wiring passes
     * {@code () -> persistenceStrategy.getSeparateTablePerEventStreamTableNameAggregates().keySet()}.
     */
    public DefaultWalMessageFilter(Supplier<Collection<String>> aggregateEventStreamTableNamesSupplier) {
        // Pre-filtering only scans tokens for "kind"/"table", so it needs a streaming parser — not the mapper's
        // configuration and not the Essentials value-type modules. Owning a plain factory keeps this filter
        // independent of which serializer the application uses.
        this.jsonFactory = new JsonFactory();
        this.aggregateEventStreamTableNamesSupplier = requireNonNull(aggregateEventStreamTableNamesSupplier,
                                                                     "aggregateEventStreamTableNamesSupplier cannot be null");
    }

    /**
     * @deprecated the serializer is no longer needed — pre-filtering uses its own streaming parser. Use
     *             {@link #DefaultWalMessageFilter(Supplier)}.
     */
    @Deprecated(forRemoval = true)
    public DefaultWalMessageFilter(JSONEventSerializer jsonSerializer,
                                   Supplier<Collection<String>> aggregateEventStreamTableNamesSupplier) {
        this(aggregateEventStreamTableNamesSupplier);
    }

    /**
     * Back-compat convenience for callers (typically tests) with a static table-name → aggregate
     * map. Wraps the keySet in a constant supplier — use the {@link Supplier} constructor when
     * runtime registrations matter.
     */
    public DefaultWalMessageFilter(JSONEventSerializer jsonSerializer,
                                   Map<String, AggregateType> aggregateEventStreamTableNames) {
        this(() -> requireNonNull(aggregateEventStreamTableNames, "aggregateEventStreamTableNames cannot be null").keySet());
    }

    /**
     * Back-compat convenience for callers (typically tests) with a static collection of table
     * names. Wraps the collection in a constant supplier — use the {@link Supplier} constructor
     * when runtime registrations matter.
     */
    public DefaultWalMessageFilter(JSONEventSerializer jsonSerializer,
                                   Collection<String> aggregateEventStreamTableNames) {
        this(() -> requireNonNull(aggregateEventStreamTableNames, "aggregateEventStreamTableNames cannot be null"));
    }

    @Override
    public boolean shouldPersist(String walJson) {
        if (walJson == null || walJson.isBlank()) {
            return false;
        }
        var trackedTables = snapshotTrackedTables();
        if (trackedTables.isEmpty()) {
            return false;
        }
        try (var parser = jsonFactory.createParser(walJson)) {
            return containsRelevantInsert(parser, trackedTables);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean shouldPersist(byte[] walJsonBytes) {
        if (walJsonBytes == null || walJsonBytes.length == 0) {
            return false;
        }
        var trackedTables = snapshotTrackedTables();
        if (trackedTables.isEmpty()) {
            return false;
        }
        try (var parser = jsonFactory.createParser(walJsonBytes)) {
            return containsRelevantInsert(parser, trackedTables);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Snapshot the current tracked-table set once per {@code shouldPersist} call. Normalises to
     * lowercase so downstream comparisons are case-insensitive without repeating the transform
     * per change entry.
     */
    private Set<String> snapshotTrackedTables() {
        var names = aggregateEventStreamTableNamesSupplier.get();
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        return names.stream()
                    .filter(Objects::nonNull)
                    .map(tableName -> tableName.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
    }

    private boolean containsRelevantInsert(JsonParser parser, Set<String> trackedTables) throws IOException {
        while (parser.nextToken() != null) {
            if (parser.currentToken() != JsonToken.FIELD_NAME || !"change".equals(parser.currentName())) {
                continue;
            }
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                parser.skipChildren();
                continue;
            }
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.START_OBJECT) {
                    parser.skipChildren();
                    continue;
                }
                String kind = null;
                String table = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) {
                        continue;
                    }
                    String field = parser.currentName();
                    JsonToken valueToken = parser.nextToken();
                    if (valueToken == null) break;
                    if ("kind".equals(field) && valueToken.isScalarValue()) {
                        kind = parser.getValueAsString();
                    } else if ("table".equals(field) && valueToken.isScalarValue()) {
                        table = parser.getValueAsString();
                    } else {
                        parser.skipChildren();
                    }
                }
                if (isInsert(kind) && isTrackedEventTable(table, trackedTables)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInsert(String kind) {
        return kind != null && "insert".equalsIgnoreCase(kind);
    }

    private static boolean isTrackedEventTable(String tableName, Set<String> trackedTables) {
        return tableName != null && trackedTables.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
