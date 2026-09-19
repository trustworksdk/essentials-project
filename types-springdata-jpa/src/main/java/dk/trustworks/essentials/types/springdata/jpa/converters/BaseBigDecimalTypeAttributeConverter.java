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
 * Base implementation for all JPA {@link AttributeConverter}'s that can convert between a concrete {@link BigDecimalType} sub-class
 * and a database {@link Double} value, which Hibernate maps to a <code>double precision</code> column.<br>
 * Example:
 * <pre>{@code
 * @Converter(autoApply = true)
 * public class AmountAttributeConverter extends BaseBigDecimalTypeAttributeConverter<Amount> {
 *     @Override
 *     protected Class<Amount> getConcreteBigDecimalType() {
 *         return Amount.class;
 *     }
 * }}</pre>
 * <br>
 * <b>⚠ This mapping is lossy, and silently so.</b> Two consequences follow from the column being binary floating point:
 * <ul>
 *     <li><b>The scale of the value written is lost.</b> {@code Amount.of("1999.50")} is read back as {@code 1999.5}.
 *         The two are numerically equal but not {@link BigDecimal#equals(Object)}-equal, because {@link BigDecimal} equality
 *         is scale-sensitive - so an assertion, a cache key or a {@code Map} lookup against the value that was written fails.</li>
 *     <li><b>Arithmetic in the database is floating point.</b> <code>sum</code>, <code>avg</code> and every SQL comparison on the
 *         column are IEEE-754 operations, so sums over many rows drift, and a value beyond roughly 15-17 significant digits
 *         cannot be represented at all.</li>
 * </ul>
 * For monetary and other exact decimal values, prefer {@link BaseBigDecimalTypeNumericAttributeConverter}, which maps to an exact
 * <code>numeric</code> column and round-trips losslessly. This class is kept as the auto-applied default so that existing schemas
 * keep working; the default changes at the next major version - see <code>docs/MIGRATION-NEXT_MAJOR.md</code>.
 *
 * @param <T> the concrete type of {@link BigDecimalType} supported by this converter
 * @see BaseBigDecimalTypeNumericAttributeConverter
 */
public abstract class BaseBigDecimalTypeAttributeConverter<T extends BigDecimalType<T>> implements AttributeConverter<T, Double> {
    @Override
    public Double convertToDatabaseColumn(T attribute) {
        return attribute != null ? attribute.doubleValue() : null;
    }

    @Override
    public T convertToEntityAttribute(Double dbData) {
        if (dbData == null) return null;
        return SingleValueType.from(BigDecimal.valueOf(dbData), getConcreteBigDecimalType());
    }

    /**
     * Override this method to return the concrete {@link BigDecimalType} sub-class  supported by this converter
     */
    protected abstract Class<T> getConcreteBigDecimalType();
}
