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
 * Receives the top-level properties of a JSON object, one at a time and in document order, from
 * {@link JSONSerializer#readProperties(byte[], JSONPropertyReader)}.
 */
@FunctionalInterface
public interface JSONPropertyReader {
    /**
     * Called once per top-level property.
     *
     * @param name  the property name
     * @param value the property's value. Valid only until this call returns, and readable at most once; a value that is
     *              not read is skipped
     */
    void read(String name, JSONPropertyValue value);
}
