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
package dk.trustworks.essentials.examples.trading.orders.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.orders.types.OrderId;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An operator case: the exchange reported a fill for an order the brokerage does not know (design §4, "a fill for an
 * order the brokerage does not know is recorded as an {@code UnknownExchangeOrder} operator case"). Its id is the
 * {@code clientOrderId} the exchange named.
 * <p>
 * Kept apart from {@link Order} on purpose: an {@code Orders} stream with no {@code OrderPlaced} would make a later
 * {@code PlaceOrder} with the same id look like a repeat and place nothing. Reached through {@link UnknownExchangeOrders}.
 */
public class UnknownExchangeOrder extends AggregateRoot<OrderId, UnknownExchangeOrderEvent, UnknownExchangeOrder> {
    private Set<ExecutionId> executions = new HashSet<>();

    protected UnknownExchangeOrder() {
    }

    /**
     * Used for rehydration
     */
    public UnknownExchangeOrder(OrderId orderId) {
        super(orderId);
    }

    public UnknownExchangeOrder(OrderId orderId, ExecutionId executionId, Quantity quantity, Money price, LocalDate tradeDate, LocalDate settlementDate) {
        super(orderId);
        recordFill(executionId, quantity, price, tradeDate, settlementDate);
    }

    /**
     * Idempotent per {@code executionId}.
     */
    public void recordFill(ExecutionId executionId, Quantity quantity, Money price, LocalDate tradeDate, LocalDate settlementDate) {
        requireNonNull(executionId, "No executionId provided");
        if (executions.contains(executionId)) {
            return;
        }
        apply(new UnknownExchangeOrderFilled(aggregateId(), executionId, quantity, price, tradeDate, settlementDate));
    }

    @EventHandler
    private void on(UnknownExchangeOrderFilled e) {
        executions.add(e.executionId());
    }
}
