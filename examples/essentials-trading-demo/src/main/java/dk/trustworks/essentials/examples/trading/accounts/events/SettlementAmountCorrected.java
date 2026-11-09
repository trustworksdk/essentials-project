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

package dk.trustworks.essentials.examples.trading.accounts.events;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Money;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The settlement of a settled trade was corrected (the exchange's {@code SettlementCorrected}, phase 4 Q-2): the cash it
 * moved changed. A fact, never refused; once per execution. Carries its own effect: {@code cashChange} on settled cash,
 * {@code costBasisChange} on the position (a buy still held), {@code realizedPnlChange} on the period's P&L (a sell, or
 * a buy no longer held). Signed amounts.
 */
public record SettlementAmountCorrected(AccountGenerationId accountStreamId,
                                        AccountId accountId,
                                        ExecutionId executionId,
                                        InstrumentId instrumentId,
                                        TradeSide side,
                                        Money cashChange,
                                        Money costBasisChange,
                                        Money realizedPnlChange) implements AccountEvent {
    public SettlementAmountCorrected {
        requireNonNull(accountStreamId, "No accountStreamId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(cashChange, "No cashChange provided");
        requireNonNull(costBasisChange, "No costBasisChange provided");
        requireNonNull(realizedPnlChange, "No realizedPnlChange provided");
    }
}
