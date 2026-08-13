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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.open_trading_account;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccount;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.open_trading_account} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 *
 * <p>Constructing the {@link TradingAccount} is this slice's decision, which is why {@code TradingAccounts.openNewAccount}
 * takes a factory rather than an account: the generation stream id does not exist until the repository has allocated
 * the first generation, so the repository allocates and this handler decides what is opened in it.
 *
 * <p>Opening is <em>not</em> idempotent here: the repository rejects a second open for an account that already has a
 * generation. That is deliberate -- an account is named by the caller, and silently swallowing a second open would
 * hide a caller that reused an id it had already used for a different owner.
 */
@Service
public class OpenTradingAccountHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(OpenTradingAccountHandler.class);

    private final TradingAccounts tradingAccounts;

    public OpenTradingAccountHandler(TradingAccounts tradingAccounts) {
        this.tradingAccounts = requireNonNull(tradingAccounts, "No tradingAccounts provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(OpenTradingAccount cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Opening new TradingAccount '{}' for owner '{}' in period '{}'",
                  cmd.accountId(),
                  cmd.ownerId(),
                  cmd.periodId());
        tradingAccounts.openNewAccount(cmd.accountId(),
                                       context -> new TradingAccount(context.streamAggregateId(),
                                                                     cmd.accountId(),
                                                                     cmd.ownerId(),
                                                                     cmd.periodId()));
    }
}
