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

package dk.trustworks.essentials.types.springdata.jpa.converters;

import dk.trustworks.essentials.types.*;
import jakarta.persistence.AttributeConverter;

import java.math.BigDecimal;

/**
 * Base implementation for all JPA {@link AttributeConverter}'s that convert between a concrete {@link BigDecimalType} sub-class
 * and a database {@link BigDecimal} value, which Hibernate maps to an exact <code>numeric</code>/<code>decimal</code> column.<br>
 * <br>
 * <b>Prefer this base class over {@link BaseBigDecimalTypeAttributeConverter} for monetary and other exact decimal values.</b>
 * {@link BaseBigDecimalTypeAttributeConverter} maps to a <code>double precision</code> column, which loses the scale of the
 * value written (<code>1999.50</code> returns as <code>1999.5</code>, and {@link BigDecimal#equals(Object)} is scale-sensitive)
 * and performs every SQL <code>sum</code>, <code>avg</code> and comparison in binary floating point.<br>
 * <br>
 * This converter is lossless in both directions: the {@link BigDecimalType}'s {@link NumberType#value()} is handed to JDBC as-is,
 * and read back unchanged.<br>
 * <br>
 * Example:
 * <pre>{@code
 * @Converter
 * public class AmountNumericAttributeConverter extends BaseBigDecimalTypeNumericAttributeConverter<Amount> {
 *     @Override
 *     protected Class<Amount> getConcreteBigDecimalType() {
 *         return Amount.class;
 *     }
 * }}</pre>
 * <br>
 * The converter deliberately does not impose a precision or a scale - a framework converter cannot know the domain's scale.
 * Declare it on the entity field the same way you would for a plain {@link BigDecimal} property:
 * <pre>{@code
 * @Entity
 * public class Order {
 *     @Convert(converter = AmountNumericAttributeConverter.class)
 *     @Column(precision = 19, scale = 2)
 *     public Amount totalPrice;
 * }}</pre>
 * Without an explicit {@link jakarta.persistence.Column} Hibernate applies its own default precision and scale, which is rarely
 * what a monetary column wants.<br>
 * <br>
 * <b>Migrating an existing column</b>: a column created by {@link BaseBigDecimalTypeAttributeConverter} is a
 * <code>double precision</code> column, so switching to this converter requires a schema migration - see
 * <code>docs/MIGRATION-NEXT_MAJOR.md</code>. Note that the migration preserves whatever is in the column; it does not repair a
 * value that floating-point accumulation has already corrupted.
 *
 * @param <T> the concrete type of {@link BigDecimalType} supported by this converter
 * @see BaseBigDecimalTypeAttributeConverter
 */
public abstract class BaseBigDecimalTypeNumericAttributeConverter<T extends BigDecimalType<T>> implements AttributeConverter<T, BigDecimal> {
    @Override
    public BigDecimal convertToDatabaseColumn(T attribute) {
        return attribute != null ? attribute.value() : null;
    }

    @Override
    public T convertToEntityAttribute(BigDecimal dbData) {
        return dbData != null ? SingleValueType.from(dbData, getConcreteBigDecimalType()) : null;
    }

    /**
     * Override this method to return the concrete {@link BigDecimalType} sub-class supported by this converter
     */
    protected abstract Class<T> getConcreteBigDecimalType();
}
