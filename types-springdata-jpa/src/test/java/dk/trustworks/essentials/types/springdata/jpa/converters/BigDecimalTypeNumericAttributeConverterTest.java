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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the <code>numeric</code>-backed converters round-trip a {@link BigDecimalType} without losing scale or
 * precision, and contrasts them with the {@link Double}-backed converters they exist to replace.
 */
class BigDecimalTypeNumericAttributeConverterTest {
    private final AmountNumericAttributeConverter     amountConverter        = new AmountNumericAttributeConverter();
    private final PercentageNumericAttributeConverter percentageConverter    = new PercentageNumericAttributeConverter();
    private final AmountAttributeConverter            doubleBackedConverter  = new AmountAttributeConverter();

    @Test
    void amount_round_trips_preserving_scale() {
        var amount = Amount.of("1999.50");

        var columnValue = amountConverter.convertToDatabaseColumn(amount);
        assertThat(columnValue).isEqualTo(new BigDecimal("1999.50"));

        // equals, not compareTo: BigDecimal.equals is scale-sensitive, and the scale is part of the value
        assertThat(amountConverter.convertToEntityAttribute(columnValue)).isEqualTo(amount);
        assertThat(amountConverter.convertToEntityAttribute(columnValue).value()).isEqualTo(new BigDecimal("1999.50"));
    }

    @Test
    void percentage_round_trips_preserving_scale() {
        var percentage = Percentage.from("2.5000%");

        var columnValue = percentageConverter.convertToDatabaseColumn(percentage);
        assertThat(columnValue).isEqualTo(percentage.value());
        assertThat(percentageConverter.convertToEntityAttribute(columnValue)).isEqualTo(percentage);
    }

    @Test
    void a_value_beyond_double_precision_round_trips_exactly() {
        var amount = Amount.of("12345678901234567.89");

        assertThat(amountConverter.convertToEntityAttribute(amountConverter.convertToDatabaseColumn(amount))).isEqualTo(amount);
    }

    @Test
    void the_double_backed_converter_loses_scale_and_precision() {
        // Documents the defect that the numeric converters exist to avoid - see docs/MIGRATION-0.60.md
        var amount = Amount.of("1999.50");
        assertThat(doubleBackedConverter.convertToEntityAttribute(doubleBackedConverter.convertToDatabaseColumn(amount)))
                .isNotEqualTo(amount)
                .isEqualTo(Amount.of("1999.5"));

        var beyondDoublePrecision = Amount.of("12345678901234567.89");
        assertThat(doubleBackedConverter.convertToEntityAttribute(doubleBackedConverter.convertToDatabaseColumn(beyondDoublePrecision)))
                .isNotEqualTo(beyondDoublePrecision);
    }

    @Test
    void null_converts_to_null_in_both_directions() {
        assertThat(amountConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(amountConverter.convertToEntityAttribute(null)).isNull();
        assertThat(percentageConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(percentageConverter.convertToEntityAttribute(null)).isNull();
    }
}
