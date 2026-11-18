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
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.types.Money;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An operator case (design Q-3): the exchange reported an execution for an order that was already closed — rejected
 * after the brokerage gave up submitting it, cancelled, expired, or filled beyond its quantity. Emitted together with
 * the {@link OrderFilled} it flags. The fill is a fact and is booked; but its hold or reservation was already released,
 * so a buy is paid from cash and a sell may exceed the shares the account had free. The order keeps its
 * {@code closedAs} status.
 */
public record OrderFilledAfterClose(OrderId orderId,
                                    AccountId accountId,
                                    InstrumentId instrumentId,
                                    TradeSide side,
                                    ExecutionId executionId,
                                    Quantity quantity,
                                    Money price,
                                    OrderStatus closedAs) implements OrderEvent {
    public OrderFilledAfterClose {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(price, "No price provided");
        requireNonNull(closedAs, "No closedAs provided");
    }
}
