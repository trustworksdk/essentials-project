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

package dk.trustworks.essentials.examples.trading.market_data.types;

import dk.trustworks.essentials.types.CharSequenceType;

/**
 * The ticker symbol an instrument trades under, e.g. {@code AAPL}.
 *
 * <p>Replaces the raw {@code String symbol} the {@code Instrument} aggregate used to carry. It serializes as the same
 * bare JSON string, so the persisted event payload is unchanged -- what changes is that a symbol can no longer be
 * passed where a display name is expected.
 */
public class Symbol extends CharSequenceType<Symbol> {
    public Symbol(String value) {
        super(value);
    }

    public Symbol(CharSequence value) {
        super(value);
    }

    public static Symbol of(CharSequence value) {
        return new Symbol(value);
    }
}
