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

import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.deser.std.StdDeserializer;
import dk.trustworks.essentials.types.*;

import java.math.BigDecimal;

public final class MoneyDeserializer extends StdDeserializer<Money> {
    private static final QuotedNumbers.Parser QUOTED_AMOUNT_PARSER = (p, text) -> new BigDecimal(text);

    public MoneyDeserializer() {
        super(Money.class);
    }

    @Override
    public Money deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        // Read as a stream: building a JsonNode tree per Money to read two properties out of it was 1.2 % of the
        // trading demo's CPU under load. Accepts a numeric amount, kept at the scale it was written with, or a quoted
        // number ("99.99", read by QuotedNumbers.readQuotedNumber, the rule NumberTypeJsonDeserializer also reads one by),
        // the properties in any order, and any other property ignored
        var        token        = p.currentToken() == JsonToken.START_OBJECT ? p.nextToken() : p.currentToken();
        BigDecimal amount       = null;
        String     currencyCode = null;
        for (; token == JsonToken.PROPERTY_NAME; token = p.nextToken()) {
            var name  = p.currentName();
            var value = p.nextToken();
            switch (name) {
                case "amount" -> {
                    if (value.isNumeric()) {
                        amount = p.getDecimalValue();
                    } else if (value == JsonToken.VALUE_STRING) {
                        // The quoted-number rule NumberTypeJsonDeserializer applies, from the same helper; a problem is
                        // reported against BigDecimal, the type the amount is read as here
                        amount = (BigDecimal) QuotedNumbers.readQuotedNumber(p, ctxt, BigDecimal.class, QUOTED_AMOUNT_PARSER, "not a valid Money 'amount'");
                    } else {
                        return (Money) ctxt.reportInputMismatch(this, "Money 'amount' must be a JSON number or a quoted number, but was %s", value);
                    }
                }
                case "currency" -> {
                    if (value == JsonToken.VALUE_STRING) {
                        currencyCode = p.getString();
                    } else {
                        // Step over an object or array value first, so the parser is not left inside it
                        p.skipChildren();
                        return (Money) ctxt.reportInputMismatch(this, "Money 'currency' must be a JSON string, but was %s", value);
                    }
                }
                default -> p.skipChildren();
            }
        }
        if (amount == null || currencyCode == null) {
            return (Money) ctxt.reportInputMismatch(this, "Money needs both 'amount' and 'currency', but got amount=%s currency=%s", amount, currencyCode);
        }
        return new Money(Amount.of(amount),
                         CurrencyCode.of(currencyCode));
    }
}
