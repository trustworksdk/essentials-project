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

package dk.trustworks.essentials.components.adminapi.rest;

import dk.trustworks.essentials.types.*;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.*;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializerBase;

/**
 * Renders the Essentials semantic value types appearing in admin DTOs as the JSON primitives the contract declares.
 * Without it a {@code QueueName} would serialize as an object wrapping its {@code value} field, which no client
 * generated from the contract can read.
 * <p>
 * The mapping mirrors {@code EssentialsValueTypeModelConverter} in {@code admin-api-spec} — the converter decides what
 * the contract's schemas say, this module decides what goes on the wire, and they have to agree:
 * {@link CharSequenceType} to string, {@link NumberType} to number.
 * <p>
 * This module deliberately does not reuse {@code EssentialTypesJacksonModule} from {@code types-jackson}/
 * {@code types-jackson3}. Those two artifacts publish the same class under the same package for different Jackson
 * majors, and a build selects exactly one of them via the {@code essentials.types-jackson.artifactId} property.
 * Depending on either would tie the HTTP layer's correctness to a choice made for the event store's serialization,
 * whereas Spring Boot's web message conversion is always Jackson 3. Only serializers are registered — no admin
 * request body carries a value type.
 */
public class AdminApiJacksonModule extends SimpleModule {

    @SuppressWarnings("unchecked")
    public AdminApiJacksonModule() {
        super("essentials-admin-api");
        addSerializer(CharSequenceType.class, new CharSequenceTypeSerializer());
        // The raw NumberType token has to be re-cast to its parameterized form to match addSerializer's signature.
        addSerializer((Class<NumberType<?, ?>>) (Class<?>) NumberType.class, new NumberTypeSerializer());
    }

    /** Serializes any {@link CharSequenceType} — {@code QueueName}, {@code LockName}, … — as a JSON string. */
    private static final class CharSequenceTypeSerializer extends ToStringSerializerBase {

        private CharSequenceTypeSerializer() {
            super(CharSequenceType.class);
        }

        @Override
        public String valueToString(Object value) {
            return value.toString();
        }
    }

    /** Serializes any {@link NumberType} — {@code GlobalEventOrder}, … — as a JSON number. */
    private static final class NumberTypeSerializer extends ValueSerializer<NumberType<?, ?>> {

        @Override
        public void serialize(NumberType<?, ?> value, JsonGenerator generator, SerializationContext context) {
            generator.writeNumber(value.value().toString());
        }
    }
}
