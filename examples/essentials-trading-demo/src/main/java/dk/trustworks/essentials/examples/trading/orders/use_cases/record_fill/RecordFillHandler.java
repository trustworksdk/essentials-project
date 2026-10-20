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

package dk.trustworks.essentials.examples.trading.orders.use_cases.record_fill;

import dk.trustworks.essentials.examples.trading.orders.aggregates.*;
import dk.trustworks.essentials.reactive.command.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Records an execution. Idempotent per {@code executionId}; a fill that arrives before the acceptance implies it.
 * <p>
 * A fill for an order the brokerage does not know is not retried — it will never become known — but recorded as an
 * {@link UnknownExchangeOrder} operator case, once per {@code executionId}.
 */
@Service
public class RecordFillHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(RecordFillHandler.class);

    private final Orders                orders;
    private final UnknownExchangeOrders unknownExchangeOrders;

    public RecordFillHandler(Orders orders, UnknownExchangeOrders unknownExchangeOrders) {
        this.orders = requireNonNull(orders, "No orders provided");
        this.unknownExchangeOrders = requireNonNull(unknownExchangeOrders, "No unknownExchangeOrders provided");
    }

    @CmdHandler
    public void handle(RecordFill cmd) {
        requireNonNull(cmd, "No cmd provided");
        orders.findOrder(cmd.orderId())
              .ifPresentOrElse(order -> order.recordFill(cmd.executionId(), cmd.quantity(), cmd.price(), cmd.tradeDate(), cmd.settlementDate()),
                               () -> {
                                   log.warn("Exchange reported execution '{}' for unknown order '{}': recorded as an operator case", cmd.executionId(), cmd.orderId());
                                   unknownExchangeOrders.recordFill(cmd.orderId(), cmd.executionId(), cmd.quantity(), cmd.price(), cmd.tradeDate(), cmd.settlementDate());
                               });
    }
}
