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

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.orders.events.UnknownExchangeOrderEvent;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.types.Money;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link UnknownExchangeOrder} operator cases and the owner of the {@code UnknownExchangeOrders}
 * stream.
 */
@Component
public class UnknownExchangeOrders {
    public static final AggregateType AGGREGATE_TYPE = OrdersAggregateTypes.UNKNOWN_EXCHANGE_ORDERS;

    private final StatefulAggregateRepository<OrderId, UnknownExchangeOrderEvent, UnknownExchangeOrder> repository;

    public UnknownExchangeOrders(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        repository = StatefulAggregateRepository.from(eventStore, AGGREGATE_TYPE, reflectionBasedAggregateRootFactory(), UnknownExchangeOrder.class);
    }

    /**
     * Opens the case on the first fill, adds to it on the next; a repeated {@code executionId} adds nothing.
     */
    public void recordFill(OrderId orderId, ExecutionId executionId, Quantity quantity, Money price, LocalDate tradeDate, LocalDate settlementDate) {
        requireNonNull(orderId, "No orderId provided");
        repository.tryLoad(orderId)
                  .ifPresentOrElse(known -> known.recordFill(executionId, quantity, price, tradeDate, settlementDate),
                                   () -> repository.save(new UnknownExchangeOrder(orderId, executionId, quantity, price, tradeDate, settlementDate)));
    }
}
