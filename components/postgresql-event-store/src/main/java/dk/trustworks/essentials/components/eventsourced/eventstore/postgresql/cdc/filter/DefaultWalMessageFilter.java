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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Filters wal2json messages so we only persist INSERT changes for configured event stream tables.
 */
public class DefaultWalMessageFilter implements WalMessageFilter {
    private final JsonFactory jsonFactory;

    private final Set<String> aggregateEventStreamTableNames;

    public DefaultWalMessageFilter(JacksonJSONEventSerializer jacksonJSONSerializer,
                                   Map<String, AggregateType> aggregateEventStreamTableNames) {
        this(jacksonJSONSerializer,
             requireNonNull(aggregateEventStreamTableNames, "aggregateEventStreamTableNames cannot be null").keySet());
    }

    public DefaultWalMessageFilter(JacksonJSONEventSerializer jacksonJSONSerializer,
                                   Collection<String> aggregateEventStreamTableNames) {
        this.jsonFactory = requireNonNull(jacksonJSONSerializer, "jacksonJSONSerializer cannot be null")
                .getObjectMapper()
                .getFactory();
        requireNonNull(aggregateEventStreamTableNames, "aggregateEventStreamTableNames cannot be null");
        this.aggregateEventStreamTableNames = aggregateEventStreamTableNames.stream()
                                                                            .filter(Objects::nonNull)
                                                                            .map(tableName -> tableName.toLowerCase(Locale.ROOT))
                                                                            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean shouldPersist(String walJson) {
        if (walJson == null || walJson.isBlank()) {
            return false;
        }
        try (var parser = jsonFactory.createParser(walJson)) {
            return containsRelevantInsert(parser);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean shouldPersist(byte[] walJsonBytes) {
        if (walJsonBytes == null || walJsonBytes.length == 0) {
            return false;
        }
        try (var parser = jsonFactory.createParser(walJsonBytes)) {
            return containsRelevantInsert(parser);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean containsRelevantInsert(JsonParser parser) throws IOException {
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
                if (isInsert(kind) && isTrackedEventTable(table)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInsert(String kind) {
        return kind != null && "insert".equalsIgnoreCase(kind);
    }

    private boolean isTrackedEventTable(String tableName) {
        return tableName != null && aggregateEventStreamTableNames.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
