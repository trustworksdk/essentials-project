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
package dk.trustworks.essentials.examples.trading.orders.views.late_fills;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.types.Money;

import java.time.OffsetDateTime;

/**
 * One execution the exchange reported for an order that was already closed ({@code closedAs}). It was booked on the
 * account without the order's hold or reservation.
 */
public record LateFill(OrderId orderId,
                       AccountId accountId,
                       InstrumentId instrumentId,
                       TradeSide side,
                       ExecutionId executionId,
                       Quantity quantity,
                       Money price,
                       OrderStatus closedAs,
                       OffsetDateTime recordedAt) {
}
