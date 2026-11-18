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
import dk.trustworks.essentials.examples.trading.orders.events.OrderEvent;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link Order}s and the owner of the {@code Orders} stream.
 */
@Component
public class Orders {
    public static final AggregateType AGGREGATE_TYPE = OrdersAggregateTypes.ORDERS;

    private final StatefulAggregateRepository<OrderId, OrderEvent, Order> repository;

    public Orders(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        repository = StatefulAggregateRepository.from(eventStore, AGGREGATE_TYPE, reflectionBasedAggregateRootFactory(), Order.class);
    }

    public Order getOrder(OrderId orderId) {
        return repository.load(requireNonNull(orderId, "No orderId provided"));
    }

    public Optional<Order> findOrder(OrderId orderId) {
        return repository.tryLoad(requireNonNull(orderId, "No orderId provided"));
    }

    public Order placeNewOrder(Order order) {
        return repository.save(requireNonNull(order, "No order provided"));
    }

    /**
     * For the exchange translation slice, which must not chase an order that is already closed. Strongly consistent,
     * unlike the {@code orders} view.
     */
    public Optional<OrderStatus> statusOf(OrderId orderId) {
        return findOrder(orderId).map(Order::status);
    }
}
