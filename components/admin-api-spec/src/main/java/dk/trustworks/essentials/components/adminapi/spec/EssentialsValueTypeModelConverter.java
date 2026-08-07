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

package dk.trustworks.essentials.components.adminapi.spec;

import com.fasterxml.jackson.databind.JavaType;
import dk.trustworks.essentials.types.*;
import io.swagger.v3.core.converter.*;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.*;

import java.time.Duration;
import java.util.Iterator;

/**
 * swagger-core {@link ModelConverter} that renders Essentials semantic {@link SingleValueType} wrappers
 * as their underlying JSON primitive instead of introspecting them as nested objects.
 * <p>
 * Without this, a {@code CharSequenceType} such as {@code QueueName} would otherwise be resolved to an
 * object schema (its internal {@code value} field). With it, the wrapper collapses to the primitive it
 * represents, so the generated contract (and the clients generated from it) expose plain strings/numbers.
 * <p>
 * Mapping:
 * <ul>
 *     <li>{@link CharSequenceType} &rarr; {@code string}</li>
 *     <li>{@link LongType} &rarr; {@code integer} ({@code int64})</li>
 *     <li>{@link IntegerType} &rarr; {@code integer} ({@code int32})</li>
 *     <li>{@link BigDecimalType} / any other {@link NumberType} &rarr; {@code number}</li>
 *     <li>{@link Duration} &rarr; {@code string} (ISO-8601, e.g. {@code PT5M})</li>
 * </ul>
 */
public class EssentialsValueTypeModelConverter implements ModelConverter {

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        JavaType javaType = Json.mapper().constructType(type.getType());
        if (javaType != null) {
            Class<?> raw = javaType.getRawClass();
            if (CharSequenceType.class.isAssignableFrom(raw)) {
                return new StringSchema();
            }
            if (LongType.class.isAssignableFrom(raw)) {
                return new IntegerSchema().format("int64");
            }
            if (IntegerType.class.isAssignableFrom(raw)) {
                return new IntegerSchema().format("int32");
            }
            if (BigDecimalType.class.isAssignableFrom(raw) || NumberType.class.isAssignableFrom(raw)) {
                return new NumberSchema();
            }
            if (Duration.class.isAssignableFrom(raw)) {
                return new StringSchema().format("duration").example("PT5M");
            }
        }
        return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    }
}
