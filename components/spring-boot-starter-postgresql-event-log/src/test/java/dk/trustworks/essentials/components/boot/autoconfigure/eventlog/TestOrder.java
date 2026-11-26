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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.types.CharSequenceType;

import java.util.UUID;

/**
 * A minimal event-sourced aggregate for the starter's integration tests.
 */
public class TestOrder extends AggregateRoot<TestOrder.OrderId, Object, TestOrder> {
    public static final AggregateType ORDERS = AggregateType.of("Orders");

    public int total;

    protected TestOrder() {
    }

    /** Used for rehydration */
    public TestOrder(OrderId orderId) {
        super(orderId);
    }

    public TestOrder(OrderId orderId, int amount) {
        this(orderId);
        apply(new OrderPlaced(orderId, amount));
    }

    public void add(int amount) {
        apply(new AmountAdded(aggregateId(), amount));
    }

    @EventHandler
    private void on(OrderPlaced e) {
        total = e.amount();
    }

    @EventHandler
    private void on(AmountAdded e) {
        total += e.amount();
    }

    public record OrderPlaced(OrderId orderId, int amount) {
    }

    public record AmountAdded(OrderId orderId, int amount) {
    }

    public static final class OrderId extends CharSequenceType<OrderId> {
        public OrderId(CharSequence value) {
            super(value);
        }

        public OrderId(String value) {
            super(value);
        }

        public static OrderId of(CharSequence value) {
            return new OrderId(value);
        }

        public static OrderId random() {
            return new OrderId(UUID.randomUUID().toString());
        }
    }
}
