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

package dk.trustworks.essentials.examples.trading.orders.use_cases.record_expiry;

import dk.trustworks.essentials.examples.trading.orders.aggregates.Orders;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Records the exchange's expiry. Idempotent; ignored for an order that already closed (filled or cancelled first).
 */
@Service
public class RecordExpiryHandler extends AnnotatedCommandHandler {
    private final Orders orders;

    public RecordExpiryHandler(Orders orders) {
        this.orders = requireNonNull(orders, "No orders provided");
    }

    @CmdHandler
    public void handle(RecordExpiry cmd) {
        requireNonNull(cmd, "No cmd provided");
        orders.getOrder(cmd.orderId()).markExpired(cmd.businessDate());
    }
}
