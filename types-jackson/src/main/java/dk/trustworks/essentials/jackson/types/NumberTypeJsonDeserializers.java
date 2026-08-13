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

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.Deserializers;
import dk.trustworks.essentials.types.NumberType;

/**
 * Resolves a {@link NumberTypeJsonDeserializer} for every concrete {@link NumberType} subclass.
 * <p>
 * This exists as a {@link Deserializers} SPI rather than a plain {@code addDeserializer(NumberType.class, …)} because
 * the two sides of Jackson are not symmetric: serializer lookup walks an object's supertypes, so registering one
 * serializer against the {@link NumberType} base covers every subclass, while deserializer lookup is an exact-type
 * match against the requested class. Registering against the base would therefore never fire for a concrete subclass.
 *
 * @see NumberTypeJsonDeserializer
 */
public class NumberTypeJsonDeserializers extends Deserializers.Base {
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public JsonDeserializer<?> findBeanDeserializer(JavaType type,
                                                    DeserializationConfig config,
                                                    BeanDescription beanDesc) {
        var rawClass = type.getRawClass();
        if (!NumberType.class.isAssignableFrom(rawClass)) {
            return null;
        }
        try {
            NumberType.resolveNumberClass(rawClass);
        } catch (IllegalArgumentException e) {
            // A type extending NumberType directly, outside the eight known bases - we cannot tell what Number it
            // wraps, so leave it to Jackson's default handling rather than guess.
            return null;
        }
        return new NumberTypeJsonDeserializer((Class) rawClass);
    }
}
