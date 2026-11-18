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

package dk.trustworks.essentials.examples.trading.orders.types;

import dk.trustworks.essentials.types.*;

import java.util.UUID;

/**
 * Identifies an order. Also its {@code clientOrderId} at the exchange (D8), the {@code HoldId} of a buy order's hold
 * and the {@code ReservationId} of a sell order's reservation on the account, so one id ties all three together.
 * Supplied by the caller, so a retried placement addresses the same order.
 */
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(String value) {
        super(value);
    }

    public OrderId(CharSequence value) {
        super(value);
    }

    public static OrderId random() {
        return new OrderId(UUID.randomUUID().toString());
    }

    public static OrderId of(CharSequence value) {
        return new OrderId(value);
    }
}
