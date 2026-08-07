/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Jackson 3 ({@code tools.jackson}) counterpart to {@link DefaultWalMessageFilter}, with identical filtering
 * semantics.
 * <p>
 * This one class is duplicated per Jackson major on purpose. Unlike the WAL converters — which parse into plain maps
 * through the {@code JSONEventSerializer} SPI and so need only one implementation — this filter deliberately scans
 * tokens without materializing the document, because it runs on every raw WAL payload before anything is persisted.
 * Preserving that streaming behaviour on both majors is worth ~40 duplicated lines; routing it through an untyped parse
 * would defeat the filter's entire purpose.
 *
 * @see DefaultWalMessageFilter
 */
public class Jackson3WalMessageFilter implements WalMessageFilter {

    private final JsonFactory                  jsonFactory;
    private final Supplier<Collection<String>> aggregateEventStreamTableNamesSupplier;

    /**
     * Primary constructor — takes a live supplier so aggregates registered at runtime are visible to filtering.
     */
    public Jackson3WalMessageFilter(Supplier<Collection<String>> aggregateEventStreamTableNamesSupplier) {
        this.jsonFactory = new JsonFactory();
        this.aggregateEventStreamTableNamesSupplier = requireNonNull(aggregateEventStreamTableNamesSupplier,
                                                                    "aggregateEventStreamTableNamesSupplier cannot be null");
    }

    /** Convenience for callers with a static table-name to aggregate map. */
    public Jackson3WalMessageFilter(Map<String, AggregateType> aggregateEventStreamTableNames) {
        this(() -> requireNonNull(aggregateEventStreamTableNames, "aggregateEventStreamTableNames cannot be null").keySet());
    }

    /** Convenience for callers with a static collection of table names. */
    public Jackson3WalMessageFilter(Collection<String> aggregateEventStreamTableNames) {
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
        try (var parser = jsonFactory.createParser(ObjectReadContext.empty(), walJson)) {
            return containsRelevantInsert(parser, trackedTables);
        } catch (JacksonException e) {
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
        try (var parser = jsonFactory.createParser(ObjectReadContext.empty(), walJsonBytes)) {
            return containsRelevantInsert(parser, trackedTables);
        } catch (JacksonException e) {
            return false;
        }
    }

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

    private boolean containsRelevantInsert(JsonParser parser, Set<String> trackedTables) {
        while (parser.nextToken() != null) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME || !"change".equals(parser.currentName())) {
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
                String kind  = null;
                String table = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                        continue;
                    }
                    String    field      = parser.currentName();
                    JsonToken valueToken = parser.nextToken();
                    if (valueToken == null) break;
                    if ("kind".equals(field) && valueToken.isScalarValue()) {
                        kind = parser.getString();
                    } else if ("table".equals(field) && valueToken.isScalarValue()) {
                        table = parser.getString();
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
