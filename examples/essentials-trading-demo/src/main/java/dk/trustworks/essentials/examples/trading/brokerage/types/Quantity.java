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

package dk.trustworks.essentials.examples.trading.brokerage.types;

import dk.trustworks.essentials.types.BigDecimalType;

import java.math.BigDecimal;

/**
 * The number of instrument units a trade is for. Distinct from {@code Amount}, which is money -- multiplying the two
 * is what produces the gross amount, and keeping them different types is what stops the two being added.
 *
 * <p>
 * The value-typed {@code (BigDecimal)} constructor is the only one the wire format depends on:
 * {@code NumberTypeJsonDeserializers} resolves a deserializer for every concrete {@code NumberType}, reads the JSON
 * number at the width the type wraps, and constructs through {@code SingleValueType.from(...)} so this type's own
 * validation still runs. The {@code (long)} overload is convenience for call sites, not a Jackson requirement --
 * see {@code LLM/LLM-types-jackson.md} -> NumberType deserialization.
 */
public class Quantity extends BigDecimalType<Quantity> {
    public static final Quantity ONE = Quantity.of(BigDecimal.ONE);

    public Quantity(BigDecimal value) {
        super(value);
    }

    public Quantity(long value) {
        super(BigDecimal.valueOf(value));
    }

    public static Quantity of(BigDecimal value) {
        return new Quantity(value);
    }

    public static Quantity of(long value) {
        return new Quantity(BigDecimal.valueOf(value));
    }
}
