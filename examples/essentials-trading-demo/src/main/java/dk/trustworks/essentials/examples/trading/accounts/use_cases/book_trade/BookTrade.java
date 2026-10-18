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

package dk.trustworks.essentials.examples.trading.accounts.use_cases.book_trade;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Money;
import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Book an execution as pending. A fact: never refused. {@code holdId} / {@code reservationId} name the order's hold or reservation to consume from, or are null.
 * <p>
 * Both the command and the request body of {@code POST /api/admin/accounts/{accountId}/trade-bookings}. Nullable: {@code holdId}, {@code reservationId}.
 */
public record BookTrade(AccountId accountId,
                        ExecutionId executionId,
                        InstrumentId instrumentId,
                        TradeSide side,
                        Quantity quantity,
                        Money price,
                        LocalDate tradeDate,
                        LocalDate settlementDate,
                        HoldId holdId,
                        ReservationId reservationId) {
    public BookTrade {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(price, "No price provided");
        requireNonNull(tradeDate, "No tradeDate provided");
        requireNonNull(settlementDate, "No settlementDate provided");
    }
}
