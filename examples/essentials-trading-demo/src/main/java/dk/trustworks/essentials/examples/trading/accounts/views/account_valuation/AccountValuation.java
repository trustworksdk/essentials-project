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

package dk.trustworks.essentials.examples.trading.accounts.views.account_valuation;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * An account's end-of-day value: its positions at the exchange's latest closing prices, plus its cash balance.
 *
 * @param businessDate the business day of the latest closing prices; {@code null} before the first close
 * @param marketValue  the positions' value; positions without a closing price count as 0 and are listed unpriced
 */
public record AccountValuation(AccountId accountId,
                               LocalDate businessDate,
                               Money cashBalance,
                               Money marketValue,
                               Money totalValue,
                               List<ValuedPosition> positions) {
    public record ValuedPosition(InstrumentId instrumentId, Quantity held, Money closingPrice, LocalDate pricedOn, Money value) {
    }
}
