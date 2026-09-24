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

import dk.trustworks.essentials.types.Amount;
import jakarta.persistence.Converter;

/**
 * Exact {@link Amount} converter that maps to a <code>numeric</code> column, as opposed to the
 * <code>double precision</code> column that the auto-applied {@link AmountAttributeConverter} maps to.<br>
 * <br>
 * This converter is <b>not</b> auto-applied - {@link AmountAttributeConverter} is, and two auto-applied converters for the same
 * type would be ambiguous. Opt in per field, and give the column the precision and scale your domain needs:
 * <pre>{@code
 * @Convert(converter = AmountNumericAttributeConverter.class)
 * @Column(precision = 19, scale = 2)
 * public Amount totalPrice;
 * }</pre>
 * An explicit {@link jakarta.persistence.Convert} takes precedence over an auto-applied converter, so the field above is mapped
 * to <code>numeric</code> even though {@link AmountAttributeConverter} is on the classpath and auto-applied.
 *
 * @see BaseBigDecimalTypeNumericAttributeConverter
 */
@Converter
public final class AmountNumericAttributeConverter extends BaseBigDecimalTypeNumericAttributeConverter<Amount> {
    @Override
    protected Class<Amount> getConcreteBigDecimalType() {
        return Amount.class;
    }
}
