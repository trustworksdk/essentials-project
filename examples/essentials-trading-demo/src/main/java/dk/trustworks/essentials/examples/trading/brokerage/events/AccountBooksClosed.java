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

package dk.trustworks.essentials.examples.trading.brokerage.events;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The books of this generation are closed. Always the last event in its stream: every command guard rejects further
 * changes once it has been applied, and the next period's events go to a new stream.
 *
 * <p>It carries its own {@code eventOrder}, which is why it is emitted through the {@code apply(eventOrder -> ...)}
 * form rather than as a plain value -- the closing entry needs to record where in the stream the books were drawn,
 * and that is only known at append time.
 */
public record AccountBooksClosed(TradingAccountGenerationId tradingAccountStreamId,
                                 TradingAccountId logicalAccountId,
                                 PeriodId nextPeriodId,
                                 EventOrder eventOrder) implements TradingAccountEvent {
    public AccountBooksClosed {
        requireNonNull(tradingAccountStreamId, "No tradingAccountStreamId provided");
        requireNonNull(logicalAccountId, "No logicalAccountId provided");
        requireNonNull(nextPeriodId, "No nextPeriodId provided");
        requireNonNull(eventOrder, "No eventOrder provided");
    }
}
