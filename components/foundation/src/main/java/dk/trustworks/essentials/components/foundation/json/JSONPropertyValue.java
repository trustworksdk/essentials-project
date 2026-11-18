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
 * The value of one top-level property, handed to a {@link JSONPropertyReader}. Valid only during that call, and
 * readable at most once.
 */
public interface JSONPropertyValue {
    /**
     * @return the value as a string, or {@code null} for a JSON {@code null}
     * @throws JSONDeserializationException if the value is neither a JSON string nor {@code null}
     * @throws IllegalStateException        if the value has already been read, or its reader call has returned
     */
    String asString();

    /**
     * @param javaType the Java type to deserialize the value into
     * @return the deserialized value
     * @throws JSONDeserializationException if the value cannot be deserialized into {@code javaType}
     * @throws IllegalStateException        if the value has already been read, or its reader call has returned
     */
    <T> T as(Class<T> javaType);

    /**
     * @param javaType the Fully Qualified Class Name of the Java type to deserialize the value into, resolved with the
     *                 serializer's {@link JSONSerializer#getClassLoader()}
     * @return the deserialized value
     * @throws JSONDeserializationException if the value cannot be deserialized into {@code javaType}
     * @throws IllegalStateException        if the value has already been read, or its reader call has returned
     */
    <T> T as(String javaType);
}
