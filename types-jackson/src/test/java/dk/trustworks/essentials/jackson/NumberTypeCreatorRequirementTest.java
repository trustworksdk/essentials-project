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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.*;
import dk.trustworks.essentials.jackson.types.*;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.*;

import java.math.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Pins {@link NumberTypeJsonDeserializer} — the counterpart to {@link NumberTypeJsonSerializer}, without which
 * {@link NumberType} subclasses fall through to Jackson 2's own creator detection.
 * <p>
 * That fallback selects a creator by the incoming JSON token's own type and does not widen, so a
 * {@link BigDecimalType} declaring only the natural {@code (BigDecimal)} constructor could not be read from an
 * integral JSON number at all. The failure mode is nasty: such a type serializes perfectly well and only breaks when
 * existing events are replayed.
 * <p>
 * The guards matter as much as the fix — see {@link RejectsLossyCoercion}. Reaching for a {@code (double)} constructor
 * used to be the obvious workaround; {@code NumberTypeCreatorPrecisionTest} in {@code types-jackson3} pins why that
 * was the wrong answer.
 */
class NumberTypeCreatorRequirementTest {
    /**
     * Mirrors the persistence mapper built by {@code EssentialsObjectMappers}.
     */
    private final ObjectMapper objectMapper = EssentialTypesJacksonModule.createObjectMapper()
                                                                        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /**
     * The natural shape — one constructor taking the wrapped value type, nothing added for Jackson's benefit.
     */
    public static class ValueTypedCreatorOnly extends BigDecimalType<ValueTypedCreatorOnly> {
        public ValueTypedCreatorOnly(BigDecimal value) {
            super(value);
        }
    }

    @Test
    void test_a_value_typed_creator_alone_reads_every_numeric_token() throws Exception {
        assertThat(objectMapper.readValue("2", ValueTypedCreatorOnly.class).value()).isEqualByComparingTo("2");
        assertThat(objectMapper.readValue("9007199254740993", ValueTypedCreatorOnly.class).value())
                .isEqualByComparingTo("9007199254740993");
        assertThat(objectMapper.readValue("2.5", ValueTypedCreatorOnly.class).value()).isEqualByComparingTo("2.5");
    }

    @Test
    void test_a_decimal_is_read_at_full_precision_rather_than_through_a_double() throws Exception {
        var highPrecision = "1234.5678901234567890123";

        assertThat(objectMapper.readValue(highPrecision, ValueTypedCreatorOnly.class).value())
                .isEqualByComparingTo(highPrecision);
        assertThat(objectMapper.readValue(highPrecision, Amount.class).value())
                .isEqualByComparingTo(highPrecision);
    }

    @Test
    void test_the_shipped_BigDecimalType_subclasses_read_every_numeric_token() throws Exception {
        assertThat(objectMapper.readValue("2", Amount.class).value()).isEqualByComparingTo("2");
        assertThat(objectMapper.readValue("9007199254740993", Amount.class).value()).isEqualByComparingTo("9007199254740993");
        assertThat(objectMapper.readValue("2.5", Amount.class).value()).isEqualByComparingTo("2.5");

        assertThat(objectMapper.readValue("2", Percentage.class).value()).isEqualByComparingTo("2.00");
        assertThat(objectMapper.readValue("9007199254740993", Percentage.class).value()).isEqualByComparingTo("9007199254740993.00");
        assertThat(objectMapper.readValue("2.5", Percentage.class).value()).isEqualByComparingTo("2.50");
    }

    @Test
    void test_a_concrete_types_own_validation_still_runs() throws Exception {
        // Percentage enforces a minimum scale of 2 in its constructor
        assertThat(objectMapper.readValue("2", Percentage.class).value().scale()).isEqualTo(2);
    }

    @Test
    void test_null_is_still_null() throws Exception {
        assertThat(objectMapper.readValue("null", Amount.class)).isNull();
        assertThat(objectMapper.readValue("null", PlainLong.class)).isNull();
    }

    /**
     * Taking over deserialization for the whole {@link NumberType} family means owning its coercion rules too. Turning
     * a hard failure into a quietly truncated value would be a far worse bug than the one being fixed — on replay it
     * is silent data corruption rather than a loud crash.
     */
    @Nested
    class RejectsLossyCoercion {
        @Test
        void test_a_fraction_is_refused_by_the_integral_types() {
            assertThatThrownBy(() -> objectMapper.readValue("2.5", PlainLong.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> objectMapper.readValue("2.5", PlainInteger.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> objectMapper.readValue("2.5", PlainBigInteger.class))
                    .isInstanceOf(JacksonException.class);
        }

        @Test
        void test_a_quoted_fraction_is_refused_too() {
            assertThatThrownBy(() -> objectMapper.readValue("\"2.5\"", PlainLong.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> objectMapper.readValue("\"2.5\"", PlainBigInteger.class))
                    .isInstanceOf(JacksonException.class);
        }

        @Test
        void test_an_overflowing_value_is_refused() {
            assertThatThrownBy(() -> objectMapper.readValue("9007199254740993", PlainInteger.class))
                    .isInstanceOf(JacksonException.class);
        }
    }

    /**
     * A quoted number is readable today through Jackson's string coercion. Anything persisted with
     * {@code WRITE_NUMBERS_AS_STRINGS}, or written by a producer that quotes large numbers, depends on it.
     */
    @Nested
    class QuotedNumbersStillRead {
        @Test
        void test_a_quoted_number_reads_into_every_family() throws Exception {
            assertThat(objectMapper.readValue("\"2\"", Amount.class).value()).isEqualByComparingTo("2");
            assertThat(objectMapper.readValue("\"2.5\"", Amount.class).value()).isEqualByComparingTo("2.5");
            assertThat(objectMapper.readValue("\"2\"", PlainLong.class).value()).isEqualTo(2L);
            assertThat(objectMapper.readValue("\"2\"", PlainBigInteger.class).value()).isEqualTo(BigInteger.TWO);
            assertThat(objectMapper.readValue("\"2.5\"", PlainDouble.class).value()).isEqualTo(2.5d);
        }
    }

    /**
     * The other {@link NumberType} bases were never broken — they take integral tokens through their own value-typed
     * constructor. They now go through the same deserializer, so they are pinned here too.
     */
    @Nested
    class AllNumberTypeBasesRoundTrip {
        @Test
        void test_each_base_reads_its_own_width() throws Exception {
            assertThat(objectMapper.readValue("2", PlainBigInteger.class).value()).isEqualTo(BigInteger.TWO);
            assertThat(objectMapper.readValue("9007199254740993", PlainBigInteger.class).value())
                    .isEqualTo(new BigInteger("9007199254740993"));
            assertThat(objectMapper.readValue("9007199254740993", PlainLong.class).value()).isEqualTo(9007199254740993L);
            assertThat(objectMapper.readValue("2", PlainInteger.class).value()).isEqualTo(2);
            assertThat(objectMapper.readValue("2.5", PlainDouble.class).value()).isEqualTo(2.5d);
            assertThat(objectMapper.readValue("2.5", PlainFloat.class).value()).isEqualTo(2.5f);
            assertThat(objectMapper.readValue("2", PlainShort.class).value()).isEqualTo((short) 2);
            assertThat(objectMapper.readValue("2", PlainByte.class).value()).isEqualTo((byte) 2);
        }
    }

    public static class PlainBigInteger extends BigIntegerType<PlainBigInteger> {
        public PlainBigInteger(BigInteger value) {
            super(value);
        }
    }

    public static class PlainLong extends LongType<PlainLong> {
        public PlainLong(Long value) {
            super(value);
        }
    }

    public static class PlainInteger extends IntegerType<PlainInteger> {
        public PlainInteger(Integer value) {
            super(value);
        }
    }

    public static class PlainDouble extends DoubleType<PlainDouble> {
        public PlainDouble(Double value) {
            super(value);
        }
    }

    public static class PlainFloat extends FloatType<PlainFloat> {
        public PlainFloat(Float value) {
            super(value);
        }
    }

    public static class PlainShort extends ShortType<PlainShort> {
        public PlainShort(Short value) {
            super(value);
        }
    }

    public static class PlainByte extends ByteType<PlainByte> {
        public PlainByte(Byte value) {
            super(value);
        }
    }
}
