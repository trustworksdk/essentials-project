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
package dk.trustworks.essentials.examples.trading.orders.events;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.orders.types.OrderId;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The exchange reported an execution for an order the brokerage has no record of. Nothing is booked — there is no
 * account to book it on — so an operator must find out whose trade it is. Recorded once per {@code executionId}.
 */
public record UnknownExchangeOrderFilled(OrderId orderId,
                                         ExecutionId executionId,
                                         Quantity quantity,
                                         Money price,
                                         LocalDate tradeDate,
                                         LocalDate settlementDate) implements UnknownExchangeOrderEvent {
    public UnknownExchangeOrderFilled {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(price, "No price provided");
        requireNonNull(tradeDate, "No tradeDate provided");
        requireNonNull(settlementDate, "No settlementDate provided");
    }
}
