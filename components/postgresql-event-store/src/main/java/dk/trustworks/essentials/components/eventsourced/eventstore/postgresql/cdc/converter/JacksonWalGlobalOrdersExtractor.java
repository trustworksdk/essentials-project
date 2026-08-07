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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import org.slf4j.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Implementation of the {@code WalGlobalOrdersExtractor} interface, responsible for
 * extracting {@code Gap} objects from logical replication messages in JSON format.
 * This implementation uses Jackson to parse the JSON data and extract key information
 * such as aggregate type and global order values.
 * <p>
 * The class expects the logical replication messages to follow a specific format,
 * containing an array of "change" events with details like the kind of operation,
 * table name, and column values. Only "insert" operations are considered for gap extraction.
 * <p>
 * Parsing goes through the {@link JSONEventSerializer} SPI into plain maps and lists rather than a Jackson tree, so the
 * same code works whichever Jackson major the application uses — the serializer injected decides.
 */
public class JacksonWalGlobalOrdersExtractor implements WalGlobalOrdersExtractor {

    private static final Logger log = LoggerFactory.getLogger(JacksonWalGlobalOrdersExtractor.class);

    private final JSONEventSerializer   jsonSerializer;
    private final AggregateTypeResolver aggregateTypeResolver;

    private final String globalOrderColumn = "global_order";

    public JacksonWalGlobalOrdersExtractor(JSONEventSerializer jsonSerializer,
                                           AggregateTypeResolver aggregateTypeResolver) {
        this.jsonSerializer = requireNonNull(jsonSerializer, "jsonSerializer cannot be null.");
        this.aggregateTypeResolver = requireNonNull(aggregateTypeResolver, "aggregateTypeResolver cannot be null.");
    }

    @Override
    public List<Gap> extract(String wal2jsonMessage) {
        if (wal2jsonMessage == null || wal2jsonMessage.isBlank()) return List.of();

        final Object root;
        try {
            root = jsonSerializer.deserialize(wal2jsonMessage, Object.class);
        } catch (Exception e) {
            // If even parsing fails, we cannot extract gaps.
            // Upstream should mark POISON and stop or continue depending on policy.
            log.debug("walGlobalOrdersExtractor failed to parse wal2json message: '{}'", e.getMessage());
            return List.of();
        }

        return extractFromRoot(root, wal2jsonMessage);
    }

    @Override
    public List<Gap> extract(byte[] wal2jsonMessageBytes) {
        if (wal2jsonMessageBytes == null || wal2jsonMessageBytes.length == 0) return List.of();

        final Object root;
        try {
            root = jsonSerializer.deserialize(wal2jsonMessageBytes, Object.class);
        } catch (Exception e) {
            log.debug("walGlobalOrdersExtractor failed to parse wal2json bytes: '{}'", e.getMessage());
            return List.of();
        }
        return extractFromRoot(root, null);
    }

    private List<Gap> extractFromRoot(Object rootNode, String wal2jsonMessageForTrace) {
        var root    = asMap(rootNode);
        var changes = asList(root == null ? null : root.get("change"));
        if (changes == null || changes.isEmpty()) return List.of();

        List<Gap> gaps = new ArrayList<>();

        for (Object changeElement : changes) {
            var change = asMap(changeElement);
            if (change == null) continue;

            String kind = text(change, "kind");
            if (!"insert".equalsIgnoreCase(kind)) continue;

            String table = text(change, "table");
            if (table == null || table.isBlank()) continue;

            AggregateType aggregateType = aggregateTypeResolver.resolveFromEventTable(table);
            if (aggregateType == null) continue;

            var names  = asList(change.get("columnnames"));
            var values = asList(change.get("columnvalues"));
            if (names == null || values == null || names.isEmpty() || names.size() != values.size()) continue;

            int globalOrderIdx = indexOf(names, globalOrderColumn);
            if (globalOrderIdx < 0) continue;

            Long globalOrder = asLong(values.get(globalOrderIdx));
            if (globalOrder == null) continue;

            gaps.add(new Gap(aggregateType, GlobalEventOrder.of(globalOrder)));
        }

        if(log.isTraceEnabled()) {
            if (wal2jsonMessageForTrace != null) {
                log.trace("Extracted {} gaps from wal2json message: {}", gaps.size(), wal2jsonMessageForTrace);
            } else {
                log.trace("Extracted {} gaps from wal2json message bytes", gaps.size());
            }
        }

        return gaps;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (value instanceof Map<?, ?> map) ? (Map<String, Object>) map : null;
    }

    private static List<?> asList(Object value) {
        return (value instanceof List<?> list) ? list : null;
    }

    private static int indexOf(List<?> names, String col) {
        for (int i = 0; i < names.size(); i++) {
            var name = names.get(i);
            if (name != null && col.equalsIgnoreCase(name.toString())) return i;
        }
        return -1;
    }

    private static Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        if (value instanceof CharSequence text) {
            String s = text.toString();
            if (s.isBlank()) return null;
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static String text(Map<String, Object> node, String key) {
        var value = node.get(key);
        return value == null ? null : value.toString();
    }
}
