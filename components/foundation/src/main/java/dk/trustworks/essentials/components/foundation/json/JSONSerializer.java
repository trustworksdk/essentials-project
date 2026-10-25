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

/**
 * JSON serializer and deserializer
 */
public interface JSONSerializer {
    /**
     * Serialize the object to JSON
     *
     * @param obj the object to serialize
     * @return the serialized json payload as a String
     * @throws JSONSerializationException in case the json couldn't be deserialized to the specified java type
     */
    String serialize(Object obj);

    /**
     * Serializes the given object into a JSON string with pretty-print formatting.
     * This method delegates to the {@code serialize} method for JSON serialization.
     *
     * @param obj the object to serialize
     * @return the serialized JSON string with pretty-print formatting
     * @throws JSONSerializationException in case the json couldn't be deserialized to the specified java type
     */
    default String serializePrettyPrint(Object obj) {
        return serialize(obj);
    }

    /**
     * Serialize the object to JSON
     *
     * @param obj the object to serialize
     * @return the serialized json payload as a byte[]
     * @throws JSONSerializationException in case the json couldn't be deserialized to the specified java type
     */
    byte[] serializeAsBytes(Object obj);

    /**
     * Deserialize the payload in the <code>json</code> parameter into the Java type specified by the Fully Qualified Class Name contained
     * in the <code>javaType</code> parameter
     *
     * @param json     the json payload
     * @param javaType the Fully Qualified Class Name for the Java type that the json payload should be deserialized into
     * @param <T>      the corresponding Java type
     * @return the deserialized json payload
     * @throws JSONDeserializationException in case the json couldn't be deserialized to the specified java type
     */
    <T> T deserialize(String json, String javaType);

    /**
     * Deserialize the payload in the <code>json</code> parameter into the Java type specified by the <code>javaType</code> parameter
     *
     * @param json     the json payload
     * @param javaType the Java type that the json payload should be deserialized into
     * @param <T>      the corresponding Java type
     * @return the deserialized json payload
     * @throws JSONDeserializationException in case the json couldn't be deserialized to the specified java type
     */
    <T> T deserialize(String json, Class<T> javaType);

    /**
     * Deserialize the payload in the <code>json</code> parameter into the Java type specified by the Fully Qualified Class Name contained
     * in the <code>javaType</code> parameter
     *
     * @param json     the json payload
     * @param javaType the Fully Qualified Class Name for the Java type that the json payload should be deserialized into
     * @param <T>      the corresponding Java type
     * @return the deserialized json payload
     * @throws JSONDeserializationException in case the json couldn't be deserialized to the specified java type
     */
    <T> T deserialize(byte[] json, String javaType);

    /**
     * Deserialize the payload in the <code>json</code> parameter into the Java type specified by the <code>javaType</code> parameter
     *
     * @param json     the json payload
     * @param javaType the Java type that the json payload should be deserialized into
     * @param <T>      the corresponding Java type
     * @return the deserialized json payload
     * @throws JSONDeserializationException in case the json couldn't be deserialized to the specified java type
     */
    <T> T deserialize(byte[] json, Class<T> javaType);

    /**
     * Reads the top-level properties of a JSON object one at a time, in document order, handing each to {@code reader},
     * which decides per property how to read its value - or not to, in which case it is skipped. This is how to read a
     * document whose shape depends on one of its own properties - a type name, a version - without deserializing it
     * twice or knowing its type up front: read the deciding property first, then the rest with what it said. The
     * deciding property has to come first in the document.
     * <p>
     * The default implementation scans the bytes for where each value ends and deserializes exactly those bytes with
     * this serializer, so it works for any implementation; one with a streaming parser should override it to read in
     * a single pass, as {@link Jackson3JSONSerializer} does.
     *
     * @param json   the JSON document, which must be an object
     * @param reader called once per top-level property
     * @throws JSONDeserializationException if {@code json} is not a well-formed JSON object, or a value cannot be read
     *                                      as asked
     */
    default void readProperties(byte[] json, JSONPropertyReader reader) {
        JSONPropertyScanner.readProperties(this, json, reader);
    }

    /**
     * Get the {@link ClassLoader} used for serializing to JSON and deserializing from JSON<br>
     * <b>Note: MUST not be null</b>
     * @return the {@link ClassLoader} used for serializing to JSON and deserializing from JSON
     */
    ClassLoader getClassLoader();

    /**
     * Set the {@link ClassLoader} used for serializing to JSON and deserializing from JSON.
     * This is e.g. required to support SpringBoot DevTools
     * @param classLoader the {@link ClassLoader} used for serializing to JSON and deserializing from JSON
     */
    void setClassLoader(ClassLoader classLoader);
}
