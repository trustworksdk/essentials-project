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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WalParserModeBytesTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    @Test
    void converter_parses_string_and_bytes_equivalently() {
        var serializer = new JacksonJSONEventSerializer(new ObjectMapper());
        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var converter = new JacksonWal2JsonToPersistedEventConverter(serializer, resolver);

        var fromString = converter.convert(validWal());
        var fromBytes = converter.convert(validWal().getBytes(StandardCharsets.UTF_8));

        assertThat(fromString).hasSize(1);
        assertThat(fromBytes).hasSize(1);
        assertThat(fromBytes.getFirst().globalEventOrder()).isEqualTo(fromString.getFirst().globalEventOrder());
        assertThat((Object) fromBytes.getFirst().eventId()).isEqualTo(fromString.getFirst().eventId());
        assertThat((Object) fromBytes.getFirst().aggregateType()).isEqualTo(fromString.getFirst().aggregateType());
    }

    @Test
    void extractor_parses_string_and_bytes_equivalently() {
        var serializer = new JacksonJSONEventSerializer(new ObjectMapper());
        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var extractor = new JacksonWalGlobalOrdersExtractor(serializer, resolver);

        var fromString = extractor.extract(validWal());
        var fromBytes = extractor.extract(validWal().getBytes(StandardCharsets.UTF_8));

        assertThat(fromString).hasSize(1);
        assertThat(fromBytes).hasSize(1);
        assertThat((Object) fromBytes.getFirst().aggregateType()).isEqualTo(fromString.getFirst().aggregateType());
        assertThat(fromBytes.getFirst().globalEventOrder()).isEqualTo(fromString.getFirst().globalEventOrder());
    }

    private static String validWal() {
        return """
               {
                 "xid": 999,
                 "nextlsn": "0/0",
                 "timestamp": "2026-01-27 15:38:10.735471+01",
                 "change": [
                   {
                     "kind": "insert",
                     "schema": "public",
                     "table": "orders_events",
                     "columnnames": ["global_order","aggregate_id","event_order","event_id","caused_by_event_id","correlation_id","event_type","event_revision","timestamp","event_payload","event_metadata","tenant"],
                     "columntypes":  ["bigint","text","bigint","text","text","text","text","integer","timestamp with time zone","jsonb","jsonb","text"],
                     "columnvalues": [
                       42,
                       "00000000-0000-0000-0000-00000000002a",
                       1,
                       "00000000-0000-0000-0000-00000000002a",
                       null,
                       null,
                       "FQCN:dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$OrderAdded",
                       1,
                       "2026-01-27 15:38:10.735471+01",
                       {"type":"OrderAdded","aggregateId":"00000000-0000-0000-0000-00000000002a"},
                       {},
                       null
                     ]
                   }
                 ]
               }
               """;
    }
}
