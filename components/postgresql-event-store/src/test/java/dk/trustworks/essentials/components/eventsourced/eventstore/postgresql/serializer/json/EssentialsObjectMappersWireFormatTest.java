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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json;

import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JSON that {@link EssentialsObjectMappers} writes for persisted payloads.
 * <p>
 * The same golden document is asserted under both Jackson flavors — the build selects one via
 * {@code essentials.types-jackson.artifactId}, so running the suite under the default profile and under
 * {@code -Pjackson3} exercises both halves against a single expected format. That is what proves an application can
 * move to Jackson 3 and still read durable-queue payloads, event payloads and metadata that Jackson 2 wrote.
 * <p>
 * The two mapper factories cannot be compared directly in one JVM: only one flavor's modules are ever on the classpath,
 * and asking for the other throws by design. The golden document is the shared reference that makes them comparable.
 * <p>
 * Regenerate deliberately, and only under the default (Jackson 2) profile, since that is the format already in
 * production databases:
 * <pre>{@code
 * mvn -pl components/postgresql-event-store test -Dtest=EssentialsObjectMappersWireFormatTest -Dwireformat.regenerate=true
 * }</pre>
 */
class EssentialsObjectMappersWireFormatTest {

    private static final String GOLDEN_RESOURCE = "/wire-format/queue-payload.json";
    private static final Path   GOLDEN_SOURCE   = Path.of("src", "test", "resources", "wire-format", "queue-payload.json");

    private final JSONSerializer serializer = EssentialsObjectMappers.createJSONSerializer();

    @Test
    void the_persisted_payload_format_is_unchanged() throws IOException {
        var serialized = serializer.serialize(payload());

        if (Boolean.getBoolean("wireformat.regenerate")) {
            Files.createDirectories(GOLDEN_SOURCE.getParent());
            Files.writeString(GOLDEN_SOURCE, serialized + System.lineSeparator(), StandardCharsets.UTF_8);
            System.out.println("Regenerated " + GOLDEN_SOURCE.toAbsolutePath());
            return;
        }

        assertThat(serialized.trim())
                .as("""
                    The persisted payload format changed. Existing durable-queue and event-store payloads will no \
                    longer deserialize, and the Jackson 2 and Jackson 3 paths will disagree. Regenerate only if that \
                    is intended.""")
                .isEqualTo(golden().trim());
    }

    @Test
    void a_payload_persisted_by_the_other_jackson_major_deserializes() throws IOException {
        var deserialized = serializer.deserialize(golden(), QueuePayload.class);

        assertThat(deserialized).isEqualTo(payload());
    }

    /**
     * Field-based access is part of the contract: {@code derived()} looks like a getter, so a mapper that auto-detects
     * getters would add a phantom {@code derived} property to every persisted document.
     */
    @Test
    void getters_are_not_serialized_as_properties() throws IOException {
        assertThat(golden()).doesNotContain("derived");
    }

    /**
     * {@link EventMetaData} <em>is</em> a {@code Map}, so its JSON form is its entries and the whole object has to be
     * handed to its constructor. The mapper settings that populate immutable payloads would otherwise reclassify it as
     * a bean with one {@code metaData} property and deserialize it by calling that constructor with {@code null}.
     * <p>
     * Asserted here rather than only in the integration tests because the break is read-only: events keep being written
     * in the right shape and only fail on the way back out, which showed up as a fetch failure in
     * {@code MultiTenantPostgresqlEventStoreIT} long after the cause.
     */
    @Test
    void event_metadata_is_serialized_as_its_entries_and_reads_back() {
        var eventMetaData = new EventMetaData(Map.of("correlation_id", "corr-1", "trace_id", "trace-1"));

        var json = serializer.serialize(eventMetaData);

        // Key order is not asserted: the backing map is a HashMap.
        assertThat(json).startsWith("{").contains("\"correlation_id\":\"corr-1\"", "\"trace_id\":\"trace-1\"");
        assertThat(serializer.deserialize(json, EventMetaData.class)).isEqualTo(eventMetaData);
    }

    private static QueuePayload payload() {
        return new QueuePayload(QueueName.of("orders"),
                                QueueEntryId.of("entry-1"),
                                "the-payload",
                                OffsetDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC),
                                Duration.ofSeconds(30),
                                Map.of(QueueName.of("orders"), 7L));
    }

    private static String golden() throws IOException {
        try (InputStream goldenDocument = EssentialsObjectMappersWireFormatTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
            assertThat(goldenDocument)
                    .as("The golden wire-format document is missing from the test classpath at %s", GOLDEN_RESOURCE)
                    .isNotNull();
            return new String(goldenDocument.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Representative of what Essentials actually persists: Essentials value types, a temporal, a {@link Duration}, and
     * a map keyed by a value type.
     */
    static final class QueuePayload {
        private QueueName              queueName;
        private QueueEntryId           id;
        private String                 payload;
        private OffsetDateTime         addedAt;
        private Duration               redeliveryDelay;
        private Map<QueueName, Long>   deliveryCounts;

        @SuppressWarnings("unused") // Jackson creator
        QueuePayload() {
        }

        QueuePayload(QueueName queueName,
                     QueueEntryId id,
                     String payload,
                     OffsetDateTime addedAt,
                     Duration redeliveryDelay,
                     Map<QueueName, Long> deliveryCounts) {
            this.queueName = queueName;
            this.id = id;
            this.payload = payload;
            this.addedAt = addedAt;
            this.redeliveryDelay = redeliveryDelay;
            this.deliveryCounts = deliveryCounts;
        }

        /** Deliberately getter-shaped and not a field — must never appear in the JSON. */
        public String derived() {
            return queueName + ":" + id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof QueuePayload that)) {
                return false;
            }
            return Objects.equals(queueName, that.queueName)
                    && Objects.equals(id, that.id)
                    && Objects.equals(payload, that.payload)
                    && Objects.equals(addedAt, that.addedAt)
                    && Objects.equals(redeliveryDelay, that.redeliveryDelay)
                    && Objects.equals(deliveryCounts, that.deliveryCounts);
        }

        @Override
        public int hashCode() {
            return Objects.hash(queueName, id, payload, addedAt, redeliveryDelay, deliveryCounts);
        }
    }
}
