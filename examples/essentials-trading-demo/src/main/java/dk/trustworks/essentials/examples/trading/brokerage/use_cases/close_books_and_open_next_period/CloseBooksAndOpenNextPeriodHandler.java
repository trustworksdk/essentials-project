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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_books_and_open_next_period;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccountNextGenerationFactory;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.close_books_and_open_next_period} slice -- one command, one
 * handler (rules/slice-design.md §R1).
 *
 * <p>Two steps, in this order and both inside the command bus's transaction: {@code closeBooks} writes the closing
 * entry into the <em>outgoing</em> stream, then {@code closeAndOpenNextGeneration} allocates the incoming one. Doing
 * only the first leaves an account with no open generation; doing only the second loses the closing entry.
 *
 * <p>Loads through {@code getAccount}, not {@code getAccountForMutation} -- the caller is asking for exactly one
 * rollover, and the automatic one would make it two.
 */
@Service
public class CloseBooksAndOpenNextPeriodHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(CloseBooksAndOpenNextPeriodHandler.class);

    private final TradingAccounts                     tradingAccounts;
    private final TradingAccountNextGenerationFactory nextGenerationFactory;

    public CloseBooksAndOpenNextPeriodHandler(TradingAccounts tradingAccounts,
                                              TradingAccountNextGenerationFactory nextGenerationFactory) {
        this.tradingAccounts = requireNonNull(tradingAccounts, "No tradingAccounts provided");
        this.nextGenerationFactory = requireNonNull(nextGenerationFactory, "No nextGenerationFactory provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(CloseBooksAndOpenNextPeriod cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Rolling books of TradingAccount '{}' into period '{}'", cmd.accountId(), cmd.nextPeriodId());
        var account = tradingAccounts.getAccount(cmd.accountId());
        account.closeBooks(cmd.nextPeriodId());
        tradingAccounts.closeAndOpenNextGeneration(cmd.accountId(),
                                                   account,
                                                   cmd.nextPeriodId(),
                                                   nextGenerationFactory);
    }
}
