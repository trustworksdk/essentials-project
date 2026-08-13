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

import dk.trustworks.essentials.types.CharSequenceType;

import java.util.UUID;

/**
 * The stable business identifier of a trading account -- the one a caller, a projection and an admin endpoint all
 * speak.
 *
 * <p>It is <em>not</em> the id the events are stored under: a trading account rolls its books, and each generation
 * gets its own event stream keyed by {@link TradingAccountGenerationId}. This id spans all of them.
 */
public class TradingAccountId extends CharSequenceType<TradingAccountId> {
    public TradingAccountId(String value) {
        super(value);
    }

    public TradingAccountId(CharSequence value) {
        super(value);
    }

    public static TradingAccountId random() {
        return new TradingAccountId(UUID.randomUUID().toString());
    }

    public static TradingAccountId of(CharSequence value) {
        return new TradingAccountId(value);
    }
}
