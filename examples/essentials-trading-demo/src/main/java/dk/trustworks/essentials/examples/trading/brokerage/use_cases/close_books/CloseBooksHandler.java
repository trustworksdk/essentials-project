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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_books;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.close_books} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 *
 * <p>Loads through {@code getAccount}, <em>not</em> {@code getAccountForMutation}. This slice is the manual
 * closing-books trigger: letting the ON_ACCESS policy roll the account first would seal a generation the caller never
 * asked about and then seal the fresh one it opened, turning one request into two rollovers.
 *
 * <p>Closing already-closed books is an idempotent no-op rather than a failure -- {@code TradingAccount.closeBooks}
 * returns early -- because an automatic rollover may have got there first, and that is not an error.
 */
@Service
public class CloseBooksHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(CloseBooksHandler.class);

    private final TradingAccounts tradingAccounts;

    public CloseBooksHandler(TradingAccounts tradingAccounts) {
        this.tradingAccounts = requireNonNull(tradingAccounts, "No tradingAccounts provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(CloseBooks cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Closing books of TradingAccount '{}', nextPeriodId={}", cmd.accountId(), cmd.nextPeriodId());
        tradingAccounts.getAccount(cmd.accountId())
                       .closeBooks(cmd.nextPeriodId());
    }
}
