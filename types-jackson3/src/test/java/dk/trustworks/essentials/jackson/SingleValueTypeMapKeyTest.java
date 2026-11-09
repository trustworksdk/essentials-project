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

import tools.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.jackson.model.*;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.InvalidFormatException;

import java.math.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * A map keyed by a value type must round-trip without any per-property annotation.
 * <p>
 * A JSON key is text, and Jackson cannot turn text into an arbitrary wrapper on its own, so this used to require
 * {@code @JsonDeserialize(keyUsing = …)} on every such property. That annotation lives in Jackson 2's
 * {@code com.fasterxml.jackson.databind.annotation} package, which Jackson 3 does not read — so on upgrade it silently
 * stops applying and previously written data becomes unreadable, reported only as "Cannot find a (Map) Key
 * deserializer". This is the regression test for handling it in the module instead: it caught aggregate snapshots
 * failing to deserialize, since those carry a {@code Map<ProductId, Integer>}.
 */
class SingleValueTypeMapKeyTest {

    private final tools.jackson.databind.ObjectMapper objectMapper = EssentialTypesJacksonModule.createObjectMapper();

    @Test
    void a_map_keyed_by_a_char_sequence_value_type_round_trips() {
        var productId = ProductId.of("product-1");
        var original  = Map.of(productId, 3);

        var json = objectMapper.writeValueAsString(original);

        assertThat(json).isEqualTo("{\"product-1\":3}");
        assertThat(objectMapper.<Map<ProductId, Integer>>readValue(json, new TypeReference<Map<ProductId, Integer>>() {}))
                .isEqualTo(original);
    }

    /** Numeric value types need the key parsed to the wrapped number before construction, not handed over as text. */
    @Test
    void a_map_keyed_by_a_numeric_value_type_round_trips() {
        var original = Map.of(Quantity.of(7), "seven");

        var json = objectMapper.writeValueAsString(original);

        assertThat(json).isEqualTo("{\"7\":\"seven\"}");
        assertThat(objectMapper.<Map<Quantity, String>>readValue(json, new TypeReference<Map<Quantity, String>>() {}))
                .isEqualTo(original);
    }

    /**
     * A key is bounded only by the name-length limit, so a numeric key is held to the number-length limit before it is
     * parsed, as a quoted number value is.
     */
    @Test
    void a_numeric_key_longer_than_the_number_length_limit_is_refused_before_it_is_parsed() {
        var tooLong    = "1".repeat(1001);
        var atTheLimit = "1".repeat(1000);

        assertThatThrownBy(() -> objectMapper.readValue("{\"" + tooLong + "\":\"x\"}", new TypeReference<Map<KeyBigInteger, String>>() {}))
                .isInstanceOf(StreamConstraintsException.class);
        assertThatThrownBy(() -> objectMapper.readValue("{\"" + tooLong + ".5\":\"x\"}", new TypeReference<Map<KeyBigDecimal, String>>() {}))
                .isInstanceOf(StreamConstraintsException.class);
        assertThat(objectMapper.<Map<KeyBigInteger, String>>readValue("{\"" + atTheLimit + "\":\"x\"}", new TypeReference<Map<KeyBigInteger, String>>() {}))
                .containsOnlyKeys(new KeyBigInteger(new BigInteger(atTheLimit)));
    }

    /**
     * A numeric key that is not a number is reported the way Jackson reports any weird key - an
     * {@link InvalidFormatException}, not a bare {@link NumberFormatException} - including over-long text with a
     * non-numeric character, which the length check refuses without parsing.
     */
    @Test
    void a_numeric_key_that_is_not_a_number_is_refused_with_an_InvalidFormatException() {
        assertThatThrownBy(() -> objectMapper.readValue("{\"abc\":\"x\"}", new TypeReference<Map<Quantity, String>>() {}))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> objectMapper.readValue("{\"2.5\":\"x\"}", new TypeReference<Map<Quantity, String>>() {}))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> objectMapper.readValue("{\"" + "a".repeat(1001) + "\":\"x\"}", new TypeReference<Map<KeyBigInteger, String>>() {}))
                .isInstanceOf(InvalidFormatException.class);
    }

    /** A registered problem handler sees a bad numeric key and the key it returns is used. */
    @Test
    void a_registered_problem_handler_is_consulted_for_a_numeric_key_that_is_not_a_number() {
        var seen = new ArrayList<String>();
        var lenientMapper = objectMapper.rebuild()
                                        .addHandler(new DeserializationProblemHandler() {
                                            @Override
                                            public Object handleWeirdKey(DeserializationContext ctxt, Class<?> rawKeyType, String keyValue, String failureMsg) {
                                                seen.add(keyValue);
                                                return new KeyBigInteger(BigInteger.ZERO);
                                            }
                                        })
                                        .build();
        var overLong = "a".repeat(1001);

        assertThat(lenientMapper.<Map<KeyBigInteger, String>>readValue("{\"abc\":\"x\"}", new TypeReference<Map<KeyBigInteger, String>>() {}))
                .containsOnlyKeys(new KeyBigInteger(BigInteger.ZERO));
        assertThat(lenientMapper.<Map<KeyBigInteger, String>>readValue("{\"" + overLong + "\":\"x\"}", new TypeReference<Map<KeyBigInteger, String>>() {}))
                .containsOnlyKeys(new KeyBigInteger(BigInteger.ZERO));
        assertThat(seen).containsExactly("abc", overLong);
    }

    public static class KeyBigInteger extends BigIntegerType<KeyBigInteger> {
        public KeyBigInteger(BigInteger value) {
            super(value);
        }
    }

    public static class KeyBigDecimal extends BigDecimalType<KeyBigDecimal> {
        public KeyBigDecimal(BigDecimal value) {
            super(value);
        }
    }
}
