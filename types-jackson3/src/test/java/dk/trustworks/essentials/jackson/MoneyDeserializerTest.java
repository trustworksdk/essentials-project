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

import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.*;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.MismatchedInputException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * Pins the input {@code MoneyDeserializer} accepts: a numeric amount kept at the scale it was written with, a quoted
 * number read to the same value, the properties in any order, other properties ignored, and both properties required.
 */
class MoneyDeserializerTest {
    private final ObjectMapper objectMapper = EssentialTypesJacksonModule.createObjectMapper();

    @Test
    void a_numeric_amount_keeps_the_scale_it_was_written_with() {
        var money = objectMapper.readValue("{\"amount\":99.90,\"currency\":\"USD\"}", Money.class);

        assertThat(money.getAmount().value()).isEqualTo(new BigDecimal("99.90"));
        assertThat((CharSequence) money.getCurrency()).isEqualTo(CurrencyCode.of("USD"));
    }

    @Test
    void a_quoted_amount_reads_to_the_same_money_as_a_numeric_one() {
        var quoted  = objectMapper.readValue("{\"amount\":\" 99.99 \",\"currency\":\"USD\"}", Money.class);
        var numeric = objectMapper.readValue("{\"amount\":99.99,\"currency\":\"USD\"}", Money.class);

        assertThat(quoted).isEqualTo(numeric);
        assertThat(quoted.getAmount().value()).isEqualTo(new BigDecimal("99.99"));
    }

    @Test
    void the_properties_are_read_in_any_order_and_other_properties_are_ignored() {
        var money = objectMapper.readValue("{\"currency\":\"DKK\",\"note\":{\"a\":[1,2]},\"amount\":\"160.49\"}", Money.class);

        assertThat(money).isEqualTo(Money.of(Amount.of("160.49"), CurrencyCode.DKK));
    }

    @Test
    void a_quoted_amount_that_is_not_a_number_is_refused() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"amount\":\"abc\",\"currency\":\"USD\"}", Money.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("abc");
    }

    @Test
    void a_problem_handler_replacement_for_a_quoted_amount_that_is_not_a_number_is_used_as_the_amount() {
        var lenientMapper = objectMapper.rebuild()
                                        .addHandler(new DeserializationProblemHandler() {
                                            @Override
                                            public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType, String valueToConvert, String failureMsg) {
                                                return targetType == BigDecimal.class ? BigDecimal.ZERO : NOT_HANDLED;
                                            }
                                        })
                                        .build();

        var money = lenientMapper.readValue("{\"amount\":\"abc\",\"currency\":\"USD\"}", Money.class);

        assertThat(money).isEqualTo(Money.of(Amount.of(BigDecimal.ZERO), CurrencyCode.of("USD")));
    }

    @Test
    void a_quoted_amount_longer_than_the_number_length_limit_is_refused_like_a_numeric_one() {
        var tooLong = "1".repeat(1001);

        assertThatThrownBy(() -> objectMapper.readValue("{\"amount\":" + tooLong + ",\"currency\":\"USD\"}", Money.class))
                .isInstanceOf(StreamConstraintsException.class);
        assertThatThrownBy(() -> objectMapper.readValue("{\"amount\":\"" + tooLong + "\",\"currency\":\"USD\"}", Money.class))
                .isInstanceOf(StreamConstraintsException.class);
        assertThatThrownBy(() -> objectMapper.readValue("{\"amount\":\"-" + tooLong + ".5\",\"currency\":\"USD\"}", Money.class))
                .isInstanceOf(StreamConstraintsException.class);
    }

    @Test
    void a_quoted_amount_at_the_number_length_limit_still_reads() {
        var atTheLimit = "1".repeat(1000);

        var money = objectMapper.readValue("{\"amount\":\"" + atTheLimit + "\",\"currency\":\"USD\"}", Money.class);

        assertThat(money.getAmount().value()).isEqualTo(new BigDecimal(atTheLimit));
    }

    @Test
    void an_amount_that_is_neither_a_number_nor_a_string_is_refused() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"amount\":true,\"currency\":\"USD\"}", Money.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("Money 'amount' must be a JSON number or a quoted number");
    }

    @Test
    void a_missing_property_is_refused() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"amount\":1.00}", Money.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("Money needs both 'amount' and 'currency'");
    }
}
