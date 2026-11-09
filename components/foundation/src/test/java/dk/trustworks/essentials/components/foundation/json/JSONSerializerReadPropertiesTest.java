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


package dk.trustworks.essentials.components.foundation.json;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link JSONSerializer#readProperties} has two implementations that must behave alike: the default every serializer
 * gets, which scans the bytes and deserializes each value on its own, and {@link Jackson3JSONSerializer}'s single-pass
 * override. Every test runs against both.
 */
class JSONSerializerReadPropertiesTest {

    static Stream<JSONSerializer> serializers() {
        var jackson = EssentialsObjectMappers.createJSONSerializer();
        return Stream.of(jackson, new DefaultOnly(jackson));
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void properties_are_read_in_document_order_each_as_the_reader_asks(JSONSerializer serializer) {
        var json = bytes("{\"type\":\"" + Order.class.getName() + "\",\"body\":{\"id\":\"o-1\",\"price\":1.10,\"tags\":[\"a\"]},\"count\":3}");
        var seen = new ArrayList<String>();
        var type = new String[1];
        var read = new Object[2];

        serializer.readProperties(json, (name, value) -> {
            seen.add(name);
            switch (name) {
                case "type" -> type[0] = value.asString();
                case "body" -> read[0] = value.as(type[0]);
                case "count" -> read[1] = value.as(Integer.class);
                default -> fail("unexpected property " + name);
            }
        });

        assertThat(seen).containsExactly("type", "body", "count");
        assertThat(read[0]).isEqualTo(new Order("o-1", new BigDecimal("1.10"), List.of("a")));
        assertThat(read[1]).isEqualTo(3);
    }

    /**
     * A value that is not read is skipped, and a bracket, quote or backslash inside one of its strings must not end it
     * early - the next property has to be found where it really starts.
     */
    @ParameterizedTest
    @MethodSource("serializers")
    void unread_values_are_skipped_whatever_their_strings_hold(JSONSerializer serializer) {
        var json = bytes("{ \"skipped\" : {\"a\":\"} ] { [ \\\" \\\\\",\"b\":[1,{\"c\":\"]\"}],\"d\":null} ,\n"
                                 + "\"alsoSkipped\":[\"}\",\"\\\"]\"], \"n\":12.5e3, \"t\":true, \"kept\":\"yes\"}");
        var kept = new ArrayList<String>();

        serializer.readProperties(json, (name, value) -> {
            if (name.equals("kept")) {
                kept.add(value.asString());
            }
        });

        assertThat(kept).containsExactly("yes");
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void strings_and_names_with_escapes_are_decoded(JSONSerializer serializer) {
        var json  = bytes("{\"we\\\"ird\\u00e6\":\"line\\nbreak \\\"quoted\\\" \\u00f8 😀\",\"plain\":\"æøå\",\"none\":null}");
        var found = new LinkedHashMap<String, String>();

        serializer.readProperties(json, (name, value) -> found.put(name, value.asString()));

        assertThat(found).containsExactly(entry("we\"irdæ", "line\nbreak \"quoted\" ø 😀"), entry("plain", "æøå"), entry("none", null));
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void an_empty_object_has_no_properties(JSONSerializer serializer) {
        var called = new ArrayList<String>();

        serializer.readProperties(bytes("  { }  "), (name, value) -> called.add(name));

        assertThat(called).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void a_value_can_be_read_once_and_only_during_its_call(JSONSerializer serializer) {
        var kept = new JSONPropertyValue[1];

        assertThatThrownBy(() -> serializer.readProperties(bytes("{\"a\":\"x\"}"), (name, value) -> {
            value.asString();
            value.asString();
        })).isInstanceOf(IllegalStateException.class);

        serializer.readProperties(bytes("{\"a\":\"x\"}"), (name, value) -> kept[0] = value);
        assertThatThrownBy(() -> kept[0].asString()).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void a_value_that_is_not_a_string_is_not_read_as_one(JSONSerializer serializer) {
        assertThatThrownBy(() -> serializer.readProperties(bytes("{\"a\":{\"b\":1}}"), (name, value) -> value.asString()))
                .isInstanceOf(JSONDeserializationException.class);
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void what_is_not_a_well_formed_object_is_refused(JSONSerializer serializer) {
        for (var json : List.of("[1,2]", "\"text\"", "{\"a\":1", "{\"a\":{\"b\":1}", "{\"a\" 1}", "{\"a\":1,}", "{\"a\":1} x", "")) {
            assertThatThrownBy(() -> serializer.readProperties(bytes(json), (name, value) -> {
            })).as(json).isInstanceOf(JSONDeserializationException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void a_value_that_does_not_fit_the_asked_type_fails_as_a_deserialization_error(JSONSerializer serializer) {
        assertThatThrownBy(() -> serializer.readProperties(bytes("{\"a\":\"not a number\"}"), (name, value) -> value.as(Integer.class)))
                .isInstanceOf(JSONDeserializationException.class);
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void an_exception_from_the_reader_reaches_the_caller_unwrapped(JSONSerializer serializer) {
        var thrown = new IllegalArgumentException("from the reader");

        assertThatThrownBy(() -> serializer.readProperties(bytes("{\"a\":1}"), (name, value) -> {
            throw thrown;
        })).isSameAs(thrown);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private record Order(String id, BigDecimal price, List<String> tags) {
    }

    /**
     * Delegates everything except {@code readProperties}, so the interface's default implementation is what runs
     */
    private record DefaultOnly(JSONSerializer delegate) implements JSONSerializer {
        @Override
        public String serialize(Object obj) {
            return delegate.serialize(obj);
        }

        @Override
        public byte[] serializeAsBytes(Object obj) {
            return delegate.serializeAsBytes(obj);
        }

        @Override
        public <T> T deserialize(String json, String javaType) {
            return delegate.deserialize(json, javaType);
        }

        @Override
        public <T> T deserialize(String json, Class<T> javaType) {
            return delegate.deserialize(json, javaType);
        }

        @Override
        public <T> T deserialize(byte[] json, String javaType) {
            return delegate.deserialize(json, javaType);
        }

        @Override
        public <T> T deserialize(byte[] json, Class<T> javaType) {
            return delegate.deserialize(json, javaType);
        }

        @Override
        public ClassLoader getClassLoader() {
            return delegate.getClassLoader();
        }

        @Override
        public void setClassLoader(ClassLoader classLoader) {
            delegate.setClassLoader(classLoader);
        }

        @Override
        public String toString() {
            return "default readProperties";
        }
    }
}
