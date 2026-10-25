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

import dk.trustworks.essentials.shared.reflection.Classes;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@link JSONSerializer#readProperties(byte[], JSONPropertyReader)} every serializer gets by default: finds where
 * each top-level value starts and ends by scanning the bytes, and hands exactly those bytes to the serializer's own
 * {@code deserialize}, so it works for any {@link JSONSerializer}. Only strings can hide a bracket, and inside a string
 * only an escaped character can hide its closing quote, so tracking those two is enough to find where an object or
 * array ends; the serializer validates each value it is asked to read. A serializer with a streaming parser should
 * override it - {@link Jackson3JSONSerializer} does - since this parses each value it reads on its own.
 */
final class JSONPropertyScanner {
    private JSONPropertyScanner() {
    }

    static void readProperties(JSONSerializer serializer, byte[] json, JSONPropertyReader reader) {
        requireNonNull(json, "No json provided");
        requireNonNull(reader, "No reader provided");
        var i = skipWhitespace(json, 0);
        expect(json, i, '{');
        i = skipWhitespace(json, i + 1);
        if (i < json.length && json[i] == '}') {
            expectEnd(json, i + 1);
            return;
        }
        while (true) {
            expect(json, i, '"');
            var nameEnd = endOfString(json, i);
            var name    = decodeString(serializer, json, i, nameEnd);
            i = skipWhitespace(json, nameEnd);
            expect(json, i, ':');
            var valueFrom = skipWhitespace(json, i + 1);
            var valueTo   = endOfValue(json, valueFrom);
            var value     = new ScannedValue(serializer, json, valueFrom, valueTo);
            try {
                reader.read(name, value);
            } finally {
                value.open = false;
            }
            i = skipWhitespace(json, valueTo);
            if (i < json.length && json[i] == ',') {
                i = skipWhitespace(json, i + 1);
            } else {
                expect(json, i, '}');
                expectEnd(json, i + 1);
                return;
            }
        }
    }

    private static final class ScannedValue implements JSONPropertyValue {
        private final JSONSerializer serializer;
        private final byte[]         json;
        private final int            from;
        private final int            to;
        private       boolean        open = true;

        private ScannedValue(JSONSerializer serializer, byte[] json, int from, int to) {
            this.serializer = serializer;
            this.json = json;
            this.from = from;
            this.to = to;
        }

        @Override
        public String asString() {
            take();
            if (json[from] == '"') {
                return decodeString(serializer, json, from, to);
            }
            if (isNull()) {
                return null;
            }
            throw new JSONDeserializationException("Expected a JSON string or null at byte " + from);
        }

        @Override
        public <T> T as(Class<T> javaType) {
            requireNonNull(javaType, "No javaType provided");
            take();
            return serializer.deserialize(Arrays.copyOfRange(json, from, to), javaType);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T as(String javaType) {
            requireNonNull(javaType, "No javaType provided");
            return (T) as(Classes.forName(javaType, serializer.getClassLoader()));
        }

        private boolean isNull() {
            return to - from == 4 && json[from] == 'n' && json[from + 1] == 'u' && json[from + 2] == 'l' && json[from + 3] == 'l';
        }

        private void take() {
            if (!open) {
                throw new IllegalStateException("A property value can be read once, and only while its reader is being called");
            }
            open = false;
        }
    }

    /**
     * @param from the opening quote
     * @param to   just past the closing quote
     */
    private static String decodeString(JSONSerializer serializer, byte[] json, int from, int to) {
        for (var i = from + 1; i < to - 1; i++) {
            if (json[i] == '\\') {
                return serializer.deserialize(Arrays.copyOfRange(json, from, to), String.class);
            }
        }
        return new String(json, from + 1, to - from - 2, StandardCharsets.UTF_8);
    }

    /**
     * @return the index just past the value starting at {@code from}
     */
    private static int endOfValue(byte[] json, int from) {
        if (from >= json.length) {
            throw malformed("a value", from);
        }
        var first = json[from];
        if (first == '"') {
            return endOfString(json, from);
        }
        if (first == '{' || first == '[') {
            var depth    = 0;
            var inString = false;
            for (var i = from; i < json.length; i++) {
                var b = json[i];
                if (inString) {
                    if (b == '\\') {
                        i++;
                    } else if (b == '"') {
                        inString = false;
                    }
                } else if (b == '"') {
                    inString = true;
                } else if (b == '{' || b == '[') {
                    depth++;
                } else if ((b == '}' || b == ']') && --depth == 0) {
                    return i + 1;
                }
            }
            throw malformed("the end of the value starting", from);
        }
        // A number, true, false or null runs to the next separator
        var i = from;
        while (i < json.length && json[i] != ',' && json[i] != '}' && !isWhitespace(json[i])) {
            i++;
        }
        if (i == from) {
            throw malformed("a value", from);
        }
        return i;
    }

    /**
     * @param from the opening quote
     * @return the index just past the closing quote
     */
    private static int endOfString(byte[] json, int from) {
        for (var i = from + 1; i < json.length; i++) {
            if (json[i] == '\\') {
                i++;
            } else if (json[i] == '"') {
                return i + 1;
            }
        }
        throw malformed("the end of the string starting", from);
    }

    private static void expect(byte[] json, int at, char expected) {
        if (at >= json.length || json[at] != expected) {
            throw malformed("'" + expected + "'", at);
        }
    }

    private static void expectEnd(byte[] json, int at) {
        if (skipWhitespace(json, at) != json.length) {
            throw malformed("the end of the document", at);
        }
    }

    private static JSONDeserializationException malformed(String expected, int at) {
        return new JSONDeserializationException("Malformed JSON object: expected " + expected + " at byte " + at);
    }

    private static int skipWhitespace(byte[] json, int from) {
        var i = from;
        while (i < json.length && isWhitespace(json[i])) {
            i++;
        }
        return i;
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }
}
