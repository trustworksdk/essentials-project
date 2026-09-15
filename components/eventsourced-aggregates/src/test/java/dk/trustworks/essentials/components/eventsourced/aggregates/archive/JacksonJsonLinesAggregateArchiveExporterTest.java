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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.GenerationState;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.LogicalAggregateId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventJSON;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventMetaDataJSON;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonJsonLinesAggregateArchiveExporterTest {
    @Test
    void it_streams_persisted_events_as_json_lines() throws IOException {
        var jsonSerializer = new JacksonJSONEventSerializer(JsonMapper.builder().findAndAddModules().build());
        var request = new AggregateArchiveExportRequest(AggregateType.of("Orders"),
                                                        "order-1",
                                                        new AggregateGeneration<>(AggregateType.of("Orders"),
                                                                                  new LogicalAggregateId<>("order-1"),
                                                                                  1L,
                                                                                  "order-1#1",
                                                                                  GenerationState.CLOSED,
                                                                                  OffsetDateTime.parse("2026-04-01T00:00:00Z"),
                                                                                  Optional.of(OffsetDateTime.parse("2026-04-10T00:00:00Z"))),
                                                        Stream.of(createPersistedEvent(jsonSerializer, 0L, 1L),
                                                                  createPersistedEvent(jsonSerializer, 1L, 2L)));

        var sink = new ByteArrayOutputStream();
        var exporter = new JacksonJsonLinesAggregateArchiveExporter(jsonSerializer);
        var count = exporter.export(request, sink);
        var lines = sink.toString(StandardCharsets.UTF_8).trim().split("\\R");

        assertThat(exporter.format()).isEqualTo(AggregateArchiveFormat.JSONL);
        assertThat(exporter.fileExtension()).isEqualTo("jsonl");
        assertThat(count).isEqualTo(2L);
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("\"aggregateType\":\"Orders\"");
        assertThat(lines[0]).contains("\"logicalAggregateId\":\"order-1\"");
        assertThat(lines[0]).contains("\"streamAggregateId\":\"order-1#1\"");
        assertThat(lines[0]).contains("\"eventTypeOrName\":\"OrderPlaced\"");
        assertThat(lines[0]).contains("\\\"quantity\\\":10");
    }

    private PersistedEvent createPersistedEvent(JacksonJSONEventSerializer jsonSerializer,
                                                long eventOrder,
                                                long globalEventOrder) {
        return PersistedEvent.from(EventId.random(),
                                   AggregateType.of("Orders"),
                                   "order-1#1",
                                   new EventJSON(jsonSerializer, EventName.of("OrderPlaced"), "{\"quantity\":10}"),
                                   EventOrder.of(eventOrder),
                                   EventRevision.of(1),
                                   GlobalEventOrder.of(globalEventOrder),
                                   new EventMetaDataJSON(jsonSerializer, "java.util.Map", "{\"source\":\"test\"}"),
                                   OffsetDateTime.parse("2026-04-15T10:15:30Z"),
                                   Optional.empty(),
                                   Optional.empty(),
                                   Optional.empty());
    }
}
