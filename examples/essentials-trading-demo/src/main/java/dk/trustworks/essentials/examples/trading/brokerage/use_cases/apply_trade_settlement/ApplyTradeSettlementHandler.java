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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.apply_trade_settlement;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.apply_trade_settlement} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 *
 * <p>The handler unpacks the command and hands the aggregate four fields; {@code TradingAccount} never names a command
 * type. Loading via {@code getAccountForMutation} means a settlement arriving after the period has run out is booked
 * into the generation that is open when it lands, not into the one the trade was placed in.
 */
@Service
public class ApplyTradeSettlementHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(ApplyTradeSettlementHandler.class);

    private final TradingAccounts tradingAccounts;

    public ApplyTradeSettlementHandler(TradingAccounts tradingAccounts) {
        this.tradingAccounts = requireNonNull(tradingAccounts, "No tradingAccounts provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(ApplyTradeSettlement cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Applying settlement of Trade '{}' to TradingAccount '{}': cashDelta={}, realizedPnlDelta={}",
                  cmd.tradeId(),
                  cmd.accountId(),
                  cmd.cashDelta(),
                  cmd.realizedPnlDelta());
        tradingAccounts.getAccountForMutation(cmd.accountId())
                       .applyTradeSettlement(cmd.tradeId(),
                                             cmd.cashDelta(),
                                             cmd.realizedPnlDelta());
    }
}
