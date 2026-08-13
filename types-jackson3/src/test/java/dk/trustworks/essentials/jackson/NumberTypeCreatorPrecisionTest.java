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

package dk.trustworks.essentials.jackson;

import dk.trustworks.essentials.jackson.types.*;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that a {@link BigDecimalType} keeps full decimal precision no matter which convenience constructors it happens
 * to declare.
 * <p>
 * Before {@link NumberTypeJsonDeserializer} existed, this was a live trap. Jackson selected a creator by the incoming
 * token's type, so declaring a {@code (double)} constructor — the obvious way to clear Jackson 2's
 * <em>"no int/Int-argument constructor"</em> error — meant every floating-point token was routed through a
 * {@code double} and narrowed before the {@link BigDecimal} was ever built. {@link Amount} declares such a
 * constructor and did lose precision this way.
 * <p>
 * The deserializer now reads each value at the width its type actually wraps and constructs through
 * {@link SingleValueType#from(Object, Class)}, so the convenience constructors are no longer part of the wire
 * contract. That is what makes {@link Amount} correct again without changing {@link Amount}.
 *
 * @see NumberTypeCreatorRequirementTest
 */
class NumberTypeCreatorPrecisionTest {
    private static final String HIGH_PRECISION = "1234.5678901234567890123";

    private final ObjectMapper objectMapper = EssentialTypesJacksonModule.createObjectMapper();

    /**
     * The natural shape.
     */
    public static class ValueTypedCreatorOnly extends BigDecimalType<ValueTypedCreatorOnly> {
        public ValueTypedCreatorOnly(BigDecimal value) {
            super(value);
        }
    }

    /**
     * The shape that used to be lossy — kept as a fixture precisely because it must no longer matter.
     */
    public static class WithDoubleCreator extends BigDecimalType<WithDoubleCreator> {
        public WithDoubleCreator(BigDecimal value) {
            super(value);
        }

        public WithDoubleCreator(long value) {
            super(BigDecimal.valueOf(value));
        }

        public WithDoubleCreator(double value) {
            super(BigDecimal.valueOf(value));
        }
    }

    @Test
    void test_the_natural_shape_preserves_a_decimal_beyond_double_precision() {
        assertThat(objectMapper.readValue(HIGH_PRECISION, ValueTypedCreatorOnly.class).value())
                .isEqualByComparingTo(HIGH_PRECISION);
    }

    @Test
    void test_a_double_constructor_no_longer_costs_precision() {
        assertThat(objectMapper.readValue(HIGH_PRECISION, WithDoubleCreator.class).value())
                .isEqualByComparingTo(HIGH_PRECISION);
    }

    /**
     * {@link Amount} declares {@code (BigDecimal)}, {@code (double)} and {@code (long)}. The {@code double} overload is
     * why it used to truncate; it is public API, so the fix had to come from the module rather than from removing it.
     */
    @Test
    void test_the_shipped_Amount_preserves_precision_despite_its_double_constructor() {
        assertThat(objectMapper.readValue(HIGH_PRECISION, Amount.class).value())
                .isEqualByComparingTo(HIGH_PRECISION);
        assertThat(objectMapper.readValue(HIGH_PRECISION, Percentage.class).value())
                .isEqualByComparingTo(HIGH_PRECISION);
    }

    @Test
    void test_every_other_numeric_token_still_reads() {
        assertThat(objectMapper.readValue("2", WithDoubleCreator.class).value()).isEqualByComparingTo("2");
        assertThat(objectMapper.readValue("9007199254740993", WithDoubleCreator.class).value())
                .isEqualByComparingTo("9007199254740993");
        assertThat(objectMapper.readValue("2.5", WithDoubleCreator.class).value()).isEqualByComparingTo("2.5");
    }
}
