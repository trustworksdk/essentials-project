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

import org.junit.jupiter.api.Test;
import tools.jackson.core.*;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.*;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * Pins {@link QuotedNumbers#readQuotedNumber}, the one quoted-number rule {@link NumberTypeJsonDeserializer} and
 * {@link MoneyDeserializer} share: an {@link ArithmeticException} from the parser is reported like a
 * {@link NumberFormatException}, through {@code handleWeirdStringValue} against the given target type, and never escapes
 * raw. {@code MoneyDeserializer}'s own parser, {@code new BigDecimal(text)}, cannot throw one, so the case is proven here
 * with a parser that can.
 */
class QuotedNumbersTest {
    /**
     * Read through the shared helper with a parser that throws {@link ArithmeticException} for a fraction.
     */
    record ExactLong(long value) {
    }

    private static final class ExactLongDeserializer extends ValueDeserializer<ExactLong> {
        @Override
        public ExactLong deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            var value = (Long) QuotedNumbers.readQuotedNumber(p, ctxt, Long.class, (parser, text) -> new BigDecimal(text).longValueExact(), "not a valid Long");
            return new ExactLong(value);
        }
    }

    private static JsonMapper mapper(DeserializationProblemHandler... handlers) {
        var builder = JsonMapper.builder()
                                .addModule(new SimpleModule().addDeserializer(ExactLong.class, new ExactLongDeserializer()));
        for (var handler : handlers) {
            builder.addHandler(handler);
        }
        return builder.build();
    }

    @Test
    void a_quoted_number_the_parser_reads_is_returned_trimmed() {
        assertThat(mapper().readValue("\" 42 \"", ExactLong.class)).isEqualTo(new ExactLong(42));
    }

    @Test
    void an_arithmetic_exception_from_the_parser_is_reported_as_text_that_is_not_a_valid_number() {
        assertThatThrownBy(() -> mapper().readValue("\"2.5\"", ExactLong.class))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("2.5")
                .hasMessageContaining("not a valid Long")
                .satisfies(e -> assertThat(((InvalidFormatException) e).getTargetType()).isEqualTo(Long.class));
    }

    @Test
    void a_problem_handler_sees_an_arithmetic_exception_from_the_parser_against_the_target_type() {
        var lenientMapper = mapper(new DeserializationProblemHandler() {
            @Override
            public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType, String valueToConvert, String failureMsg) {
                return targetType == Long.class && "2.5".equals(valueToConvert) ? 2L : NOT_HANDLED;
            }
        });

        assertThat(lenientMapper.readValue("\"2.5\"", ExactLong.class)).isEqualTo(new ExactLong(2));
    }

    @Test
    void text_that_is_not_a_number_is_reported_the_same_way() {
        assertThatThrownBy(() -> mapper().readValue("\"abc\"", ExactLong.class))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("not a valid Long");
    }

    @Test
    void over_long_number_shaped_text_is_refused_before_the_parser_runs() {
        assertThatThrownBy(() -> mapper().readValue("\"" + "1".repeat(1001) + "\"", ExactLong.class))
                .isInstanceOf(StreamConstraintsException.class);
    }
}
