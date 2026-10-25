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


package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class MessageEnvelopeTest {

    private final JSONSerializer jsonSerializer = EssentialsObjectMappers.createJSONSerializer();

    @Test
    void an_unordered_message_round_trips_in_the_current_format() {
        var metaData = MessageMetaData.of("correlationId", "c-1", "traceparent", "00-4bf9-01");
        var message  = new Message(new OrderPlaced("o-1", new BigDecimal("612.40"), List.of("desk-a")), metaData);

        var read = roundTrip(message);

        assertThat(read).isNotInstanceOf(OrderedMessage.class);
        assertThat(read.getPayload()).isEqualTo(message.getPayload());
        assertThat(read.getMetaData()).isEqualTo(metaData);
    }

    @Test
    void an_ordered_message_comes_back_ordered_with_the_key_and_order_it_is_given() {
        var message = OrderedMessage.of(new OrderPlaced("o-1", BigDecimal.ONE, List.of()), "o-1", 7);

        var read = MessageEnvelope.deserialize(jsonSerializer, MessageEnvelope.serialize(jsonSerializer, message),
                                               MessageEnvelope.FORMAT_VERSION, "o-1", 7);

        assertThat(read).isInstanceOf(OrderedMessage.class);
        assertThat(((OrderedMessage) read).getKey()).isEqualTo("o-1");
        assertThat(((OrderedMessage) read).getOrder()).isEqualTo(7);
        assertThat(read.getPayload()).isEqualTo(message.getPayload());
    }

    /**
     * The point of the format: the payload and metadata are JSON inside the envelope, not JSON strings, so neither is
     * escaped and nothing is serialized twice.
     */
    @Test
    void the_payload_and_metadata_are_embedded_as_json_not_as_strings() {
        var message = new Message(new OrderPlaced("o-1", new BigDecimal("1.10"), List.of("a")), MessageMetaData.of("k", "v"));

        var stored = new String(MessageEnvelope.serialize(jsonSerializer, message), StandardCharsets.UTF_8);

        assertThat(stored).isEqualTo("{\"payloadType\":\"" + OrderPlaced.class.getName() + "\","
                                             + "\"payload\":" + jsonSerializer.serialize(message.getPayload()) + ","
                                             + "\"metaData\":{\"k\":\"v\"}}");
        assertThat(stored).doesNotContain("\\\"");
    }

    /**
     * Where the payload ends is found by scanning for its closing bracket, so a bracket, quote or backslash inside one
     * of its strings must not end it early.
     */
    @Test
    void brackets_quotes_and_backslashes_inside_payload_strings_do_not_end_the_payload() {
        var awkward = "} ] { [ \" \\\" \\ \",\"metaData\":{} æøå   😀";
        var message = new Message(new Note(awkward, Map.of("}", List.of("\"]", "\\")), new String[]{"{", "]"}),
                                  MessageMetaData.of("note", awkward));

        var read = roundTrip(message);

        var note = (Note) read.getPayload();
        assertThat(note.text()).isEqualTo(awkward);
        assertThat(note.nested()).isEqualTo(Map.of("}", List.of("\"]", "\\")));
        assertThat(note.brackets()).containsExactly("{", "]");
        assertThat(read.getMetaData()).containsEntry("note", awkward);
    }

    @Test
    void payloads_that_are_not_json_objects_round_trip() {
        assertThat(roundTrip(new Message("a \"plain\" } string")).getPayload()).isEqualTo("a \"plain\" } string");
        assertThat(roundTrip(new Message(42)).getPayload()).isEqualTo(42);
        assertThat(roundTrip(new Message(new ArrayList<>(List.of("x", "]")))).getPayload()).isEqualTo(List.of("x", "]"));
    }

    @Test
    void whitespace_around_the_payload_is_tolerated() {
        var stored = ("{\"payloadType\":\"" + OrderPlaced.class.getName() + "\",\"payload\":\n  "
                + "{ \"orderId\" : \"o-1\", \"price\" : 2.50, \"tags\" : [ ] }  \n,\"metaData\":{\"k\": \"v\"}}").getBytes(StandardCharsets.UTF_8);

        var read = MessageEnvelope.deserialize(jsonSerializer, stored, MessageEnvelope.FORMAT_VERSION, null, 0);

        assertThat(read.getPayload()).isEqualTo(new OrderPlaced("o-1", new BigDecimal("2.50"), List.of()));
        assertThat(read.getMetaData()).containsEntry("k", "v");
    }

    /**
     * Exactly the bytes 0.60.0 wrote - format 1, payload and metadata as JSON strings. A row like this can still be in a
     * queue or its dead-letter table, so it must stay readable for good. Written out literally, not produced by any code
     * that still exists, so a change to the reader cannot quietly change what "format 1" means.
     */
    @Test
    void a_message_written_by_0_60_0_in_format_1_is_still_read() {
        var stored = ("{\"payloadType\":\"" + OrderPlaced.class.getName() + "\","
                + "\"payload\":\"{\\\"orderId\\\":\\\"o-1\\\",\\\"price\\\":612.40,\\\"tags\\\":[\\\"desk-a\\\"]}\","
                + "\"metaData\":\"{\\\"correlationId\\\":\\\"c-1\\\"}\"}").getBytes(StandardCharsets.UTF_8);

        var read = MessageEnvelope.deserialize(jsonSerializer, stored, MessageEnvelope.FORMAT_VERSION_1, "o-1", 3);

        assertThat(read).isInstanceOf(OrderedMessage.class);
        assertThat(read.getPayload()).isEqualTo(new OrderPlaced("o-1", new BigDecimal("612.40"), List.of("desk-a")));
        assertThat(read.getMetaData()).containsEntry("correlationId", "c-1");
    }

    @Test
    void a_format_1_message_without_metadata_comes_back_with_empty_metadata() {
        var stored = ("{\"payloadType\":\"java.lang.String\",\"payload\":\"\\\"hello\\\"\",\"metaData\":null}").getBytes(StandardCharsets.UTF_8);

        var read = MessageEnvelope.deserialize(jsonSerializer, stored, MessageEnvelope.FORMAT_VERSION_1, null, 0);

        assertThat(read.getPayload()).isEqualTo("hello");
        assertThat(read.getMetaData()).isEmpty();
    }

    @Test
    void only_the_two_known_formats_are_readable() {
        assertThat(MessageEnvelope.isReadable(MessageEnvelope.FORMAT_VERSION)).isTrue();
        assertThat(MessageEnvelope.isReadable(MessageEnvelope.FORMAT_VERSION_1)).isTrue();
        assertThat(MessageEnvelope.isReadable(0)).isFalse();
        assertThat(MessageEnvelope.isReadable(3)).isFalse();
        assertThatThrownBy(() -> MessageEnvelope.deserialize(jsonSerializer, "{}".getBytes(StandardCharsets.UTF_8), 3, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format 3");
    }

    @Test
    void a_malformed_envelope_is_refused_rather_than_misread() {
        var valid = MessageEnvelope.serialize(jsonSerializer, new Message(new OrderPlaced("o-1", BigDecimal.ONE, List.of())));
        var truncated = Arrays.copyOf(valid, valid.length - 20);
        var format1   = "{\"payloadType\":\"x\",\"payload\":\"{}\",\"metaData\":null}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> MessageEnvelope.deserialize(jsonSerializer, truncated, MessageEnvelope.FORMAT_VERSION, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        // A format-1 row read as the current format: its payload is a string, so the metaData that follows it is not found
        assertThatThrownBy(() -> MessageEnvelope.deserialize(jsonSerializer, "{\"wrong\":1}".getBytes(StandardCharsets.UTF_8), MessageEnvelope.FORMAT_VERSION, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageEnvelope.deserialize(jsonSerializer, format1, MessageEnvelope.FORMAT_VERSION, null, 0))
                .isInstanceOf(RuntimeException.class);
    }

    private Message roundTrip(Message message) {
        return MessageEnvelope.deserialize(jsonSerializer, MessageEnvelope.serialize(jsonSerializer, message), MessageEnvelope.FORMAT_VERSION, null, 0);
    }

    private record OrderPlaced(String orderId, BigDecimal price, List<String> tags) {
    }

    private record Note(String text, Map<String, List<String>> nested, String[] brackets) {
    }
}
