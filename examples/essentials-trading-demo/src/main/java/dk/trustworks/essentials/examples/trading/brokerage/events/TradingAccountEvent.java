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

import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;

/**
 * The set of events a {@code TradingAccount} can emit is closed, so the interface is {@code sealed}: adding a variant
 * means updating the {@code permits} clause, which is a compile error away rather than a silent omission. Sealing does
 * not restrict the EventStore, which deserializes the concrete records reflectively by their fully qualified class
 * name.
 *
 * <p>Every variant carries <em>two</em> ids, and they are not interchangeable. {@link #tradingAccountStreamId()} is
 * the stream the event was appended to -- one per books generation -- and {@link #logicalAccountId()} is the account
 * that stream belongs to. A projection that wants a per-account running total keys on the logical id; one that wants
 * to reason about a single generation keys on the stream id.
 */
public sealed interface TradingAccountEvent permits TradingAccountOpened,
                                                    CashDeposited,
                                                    FundsReserved,
                                                    FundsReleased,
                                                    TradeSettlementApplied,
                                                    AccountBooksClosed {

    TradingAccountGenerationId tradingAccountStreamId();

    TradingAccountId logicalAccountId();
}
