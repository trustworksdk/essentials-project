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

import java.util.UUID;

/**
 * Identifier for an instrument.
 *
 * <p>This is the one type the {@code brokerage} bounded context imports from {@code market_data} -- a trade names the
 * instrument it was placed against. It lives in {@code types/}, which together with {@code events/} is the only
 * importable surface of this context, so that import is legal and deliberate.
 *
 * <p>It is also the aggregate id of <em>both</em> aggregates in this context: {@code Instrument} (reference data) and
 * {@code InstrumentPrice} (the latest price). They are separate consistency boundaries and separate event streams;
 * sharing the id is what lets a price stream be found from an instrument without a lookup table.
 */
public class InstrumentId extends CharSequenceType<InstrumentId> {
    public InstrumentId(String value) {
        super(value);
    }

    public InstrumentId(CharSequence value) {
        super(value);
    }

    public static InstrumentId random() {
        return new InstrumentId(UUID.randomUUID().toString());
    }

    public static InstrumentId of(CharSequence value) {
        return new InstrumentId(value);
    }
}
