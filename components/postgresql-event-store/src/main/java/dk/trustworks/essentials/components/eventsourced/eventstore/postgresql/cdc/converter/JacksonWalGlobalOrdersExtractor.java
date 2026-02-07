/*
 *  Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import org.slf4j.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class JacksonWalGlobalOrdersExtractor implements WalGlobalOrdersExtractor {

    private static final Logger log = LoggerFactory.getLogger(JacksonWalGlobalOrdersExtractor.class);

    private final JacksonJSONEventSerializer jacksonJSONSerializer;
    private final AggregateTypeResolver      aggregateTypeResolver;

    private final String globalOrderColumn = "global_order";

    public JacksonWalGlobalOrdersExtractor(JacksonJSONEventSerializer jacksonJSONSerializer,
                                           AggregateTypeResolver aggregateTypeResolver) {
        this.jacksonJSONSerializer = requireNonNull(jacksonJSONSerializer, "jacksonJSONSerializer cannot be null.");
        this.aggregateTypeResolver = requireNonNull(aggregateTypeResolver, "aggregateTypeResolver cannot be null.");
    }

    @Override
    public List<Gap> extract(String wal2jsonMessage) {
        if (wal2jsonMessage == null || wal2jsonMessage.isBlank()) return List.of();

        final JsonNode root;
        try {
            root = jacksonJSONSerializer.getObjectMapper().readTree(wal2jsonMessage);
        } catch (Exception e) {
            // If even parsing fails, we cannot extract gaps.
            // Upstream should mark POISON and stop or continue depending on policy.
            log.debug("walGlobalOrdersExtractor failed to parse wal2json message: '{}'", e.getMessage());
            return List.of();
        }

        JsonNode changeNode = root.get("change");
        if (!(changeNode instanceof ArrayNode changes) || changes.isEmpty()) return List.of();

        List<Gap> gaps = new ArrayList<>();

        for (JsonNode change : changes) {
            String kind = text(change, "kind");
            if (!"insert".equalsIgnoreCase(kind)) continue;

            String table = text(change, "table");
            if (table == null || table.isBlank()) continue;

            AggregateType aggregateType = aggregateTypeResolver.resolveFromEventTable(table);
            if (aggregateType == null) continue;

            ArrayNode names = asArray(change.get("columnnames"));
            ArrayNode values = asArray(change.get("columnvalues"));
            if (names == null || values == null || names.isEmpty() || names.size() != values.size()) continue;

            int globalOrderIdx = indexOf(names, globalOrderColumn);
            if (globalOrderIdx < 0) continue;

            JsonNode v = values.get(globalOrderIdx);
            Long globalOrder = asLong(v);
            if (globalOrder == null) continue;

            gaps.add(new Gap(aggregateType, GlobalEventOrder.of(globalOrder)));
        }

        if(log.isTraceEnabled()) {
            log.trace("Extracted {} gaps from wal2json message: {}", gaps.size(), wal2jsonMessage);
        }

        return gaps;
    }

    private static ArrayNode asArray(JsonNode n) {
        return (n instanceof ArrayNode a) ? a : null;
    }

    private static int indexOf(ArrayNode names, String col) {
        for (int i = 0; i < names.size(); i++) {
            if (col.equalsIgnoreCase(names.get(i).asText())) return i;
        }
        return -1;
    }

    private static Long asLong(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.longValue();
        if (n.isTextual()) {
            String s = n.asText();
            if (s == null || s.isBlank()) return null;
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }
}
