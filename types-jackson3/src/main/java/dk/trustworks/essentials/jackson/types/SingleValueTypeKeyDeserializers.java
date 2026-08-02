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

package dk.trustworks.essentials.jackson.types;

import dk.trustworks.essentials.types.*;
import tools.jackson.databind.*;
import tools.jackson.databind.deser.KeyDeserializers;

import java.math.*;

/**
 * Deserializes a JSON object key back into the {@link SingleValueType} it was written from, so
 * {@code Map<ProductId, Integer>} round-trips without any per-class annotation.
 * <p>
 * A JSON key is always text, and Jackson has no built-in way to turn text into an arbitrary wrapper type, so without
 * this a value-type-keyed map fails on read with <em>"Cannot find a (Map) Key deserializer"</em> — while serialization
 * happily writes the key. Under Jackson 2 the workaround was
 * {@code @JsonDeserialize(keyUsing = SomeIdKeyDeserializer.class)} per property. That annotation lives in
 * {@code com.fasterxml.jackson.databind.annotation}, which Jackson 3 does not read: the annotation silently stops
 * applying on upgrade and the failure shows up as unreadable persisted data. Handling it here means it neither needs an
 * annotation nor can be forgotten.
 * <p>
 * An explicit {@code keyUsing} on a property still wins — Jackson consults annotations before these providers.
 *
 * @see EssentialTypesJacksonModule
 */
final class SingleValueTypeKeyDeserializers implements KeyDeserializers {

    @Override
    public KeyDeserializer findKeyDeserializer(JavaType type,
                                               DeserializationConfig config,
                                               BeanDescription.Supplier beanDescRef) {
        var rawType = type.getRawClass();
        if (!SingleValueType.class.isAssignableFrom(rawType)) {
            return null; // Not ours — leave the type to the standard resolution.
        }
        return new KeyDeserializer() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object deserializeKey(String key, DeserializationContext context) {
                var value = rawValueOf(key, rawType);
                if (value == null) {
                    // An unsupported value-type family: report it the way Jackson would have, rather than guessing.
                    return context.handleWeirdKey(rawType,
                                                  key,
                                                  "Cannot convert the key to %s: unsupported %s value type",
                                                  rawType.getName(),
                                                  SingleValueType.class.getSimpleName());
                }
                return SingleValueType.fromObject(value, (Class) rawType);
            }
        };
    }

    /**
     * Converts the textual key into the type the value type wraps, since {@link SingleValueType#fromObject} matches the
     * constructor by argument type — handing a {@link String} to a {@link LongType} would find nothing.
     */
    private static Object rawValueOf(String key, Class<?> rawType) {
        if (CharSequenceType.class.isAssignableFrom(rawType)) {
            return key;
        }
        if (LongType.class.isAssignableFrom(rawType)) {
            return Long.valueOf(key);
        }
        if (IntegerType.class.isAssignableFrom(rawType)) {
            return Integer.valueOf(key);
        }
        if (ShortType.class.isAssignableFrom(rawType)) {
            return Short.valueOf(key);
        }
        if (ByteType.class.isAssignableFrom(rawType)) {
            return Byte.valueOf(key);
        }
        if (DoubleType.class.isAssignableFrom(rawType)) {
            return Double.valueOf(key);
        }
        if (FloatType.class.isAssignableFrom(rawType)) {
            return Float.valueOf(key);
        }
        if (BigDecimalType.class.isAssignableFrom(rawType)) {
            return new BigDecimal(key);
        }
        if (BigIntegerType.class.isAssignableFrom(rawType)) {
            return new BigInteger(key);
        }
        if (BooleanType.class.isAssignableFrom(rawType)) {
            return Boolean.valueOf(key);
        }
        return null;
    }
}
