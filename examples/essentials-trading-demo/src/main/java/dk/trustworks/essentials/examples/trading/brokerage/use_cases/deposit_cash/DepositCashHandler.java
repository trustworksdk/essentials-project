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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.deposit_cash;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.deposit_cash} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 *
 * <p>Loads through {@code getAccountForMutation}, so an account whose accounting period has run out rolls its books
 * before the deposit is booked and the cash lands in the new generation. That rollover is a property of loading an
 * account for change, not a decision of this slice -- see {@code TradingAccounts.getAccountForMutation}.
 */
@Service
public class DepositCashHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(DepositCashHandler.class);

    private final TradingAccounts tradingAccounts;

    public DepositCashHandler(TradingAccounts tradingAccounts) {
        this.tradingAccounts = requireNonNull(tradingAccounts, "No tradingAccounts provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(DepositCash cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Depositing {} into TradingAccount '{}'", cmd.amount(), cmd.accountId());
        tradingAccounts.getAccountForMutation(cmd.accountId())
                       .depositCash(cmd.amount());
    }
}
