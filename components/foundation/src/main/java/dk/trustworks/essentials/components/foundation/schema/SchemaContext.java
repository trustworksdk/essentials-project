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
package dk.trustworks.essentials.components.foundation.schema;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What the {@link EssentialsSchemaHarness} hands every {@link EssentialsSchemaContributor}: shared collaborators a
 * contribution may need, looked up by type. Most contributors already hold everything they describe - their table
 * names, their serializer - and ignore it; the context exists so one that does not can get it without a wider
 * {@link EssentialsSchemaContributor#contribute(SchemaContext)} signature.
 */
public final class SchemaContext {
    private static final SchemaContext EMPTY = new SchemaContext(Map.of());

    private final Map<Class<?>, Object> values;

    private SchemaContext(Map<Class<?>, Object> values) {
        this.values = values;
    }

    /**
     * @return a context holding nothing
     */
    public static SchemaContext empty() {
        return EMPTY;
    }

    /**
     * @return a copy of this context that also holds {@code value} under {@code type}
     */
    public <T> SchemaContext with(Class<T> type, T value) {
        requireNonNull(type, "No type provided");
        requireNonNull(value, "No value provided for {}", type.getName());
        var copy = new HashMap<>(values);
        copy.put(type, value);
        return new SchemaContext(Map.copyOf(copy));
    }

    /**
     * @return the value held under {@code type}, if any
     */
    public <T> Optional<T> find(Class<T> type) {
        requireNonNull(type, "No type provided");
        return Optional.ofNullable(type.cast(values.get(type)));
    }
}
