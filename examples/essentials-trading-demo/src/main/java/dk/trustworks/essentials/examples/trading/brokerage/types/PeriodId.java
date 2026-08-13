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

/**
 * Names the accounting period a trading account's books are currently open in, e.g. {@code 2026-08}.
 *
 * <p>Closing books ends one period and opens the next. The framework's closing-books evaluator is {@code String}-typed
 * for the period, so {@code TradingAccountClosingBooksPolicy} converts at its two seams rather than letting the raw
 * {@code String} spread into the aggregate.
 */
public class PeriodId extends CharSequenceType<PeriodId> {
    public PeriodId(String value) {
        super(value);
    }

    public PeriodId(CharSequence value) {
        super(value);
    }

    public static PeriodId of(CharSequence value) {
        return new PeriodId(value);
    }
}
