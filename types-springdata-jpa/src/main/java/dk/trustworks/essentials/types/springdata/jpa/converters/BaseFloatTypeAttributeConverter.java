/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.types.springdata.jpa.converters;

import dk.trustworks.essentials.types.*;
import jakarta.persistence.AttributeConverter;

/**
 * Base implementation for all JPA {@link AttributeConverter}'s that can convert between a concrete {@link FloatType} sub-class
 * and a database {@link Long} value.<br>
 * Example:
 * <pre>{@code
 * @Converter(autoApply = true)
 * public class AmountAttributeConverter extends BaseFloatTypeAttributeConverter<Amount> {
 *     @Override
 *     protected Class<Amount> getConcreteFloatType() {
 *         return Amount.class;
 *     }
 * }}</pre>
 *
 * @param <T> the concrete type of {@link FloatType} supported by this converter
 */
public abstract class BaseFloatTypeAttributeConverter<T extends FloatType<T>> implements AttributeConverter<T, Float> {
    @Override
    public Float convertToDatabaseColumn(T attribute) {
        return attribute != null ? attribute.floatValue() : null;
    }

    @Override
    public T convertToEntityAttribute(Float dbData) {
        if (dbData == null) return null;
        return SingleValueType.from(dbData, getConcreteFloatType());
    }

    /**
     * Override this method to return the concrete {@link FloatType} sub-class  supported by this converter
     */
    protected abstract Class<T> getConcreteFloatType();
}
