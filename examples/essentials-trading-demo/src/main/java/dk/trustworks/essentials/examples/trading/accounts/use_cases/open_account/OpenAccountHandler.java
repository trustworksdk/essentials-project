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

package dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account;

import dk.trustworks.essentials.examples.trading.accounts.aggregates.*;
import dk.trustworks.essentials.examples.trading.accounts.types.OpeningBalances;
import dk.trustworks.essentials.reactive.command.*;
import dk.trustworks.essentials.types.Money;
import org.slf4j.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The only slice that constructs an {@link Account}. Opens it in the current period of the closing-books policy, with
 * empty balances; an account that already exists is left alone, so a retried open is harmless.
 */
@Service
public class OpenAccountHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(OpenAccountHandler.class);

    private final Accounts                  accounts;
    private final AccountClosingBooksPolicy closingBooksPolicy;
    private final Clock                     clock;

    public OpenAccountHandler(Accounts accounts, AccountClosingBooksPolicy closingBooksPolicy, Clock clock) {
        this.accounts = requireNonNull(accounts, "No accounts provided");
        this.closingBooksPolicy = requireNonNull(closingBooksPolicy, "No closingBooksPolicy provided");
        this.clock = requireNonNull(clock, "No clock provided");
    }

    @CmdHandler
    public void handle(OpenAccount cmd) {
        requireNonNull(cmd, "No cmd provided");
        if (accounts.findAccount(cmd.accountId()).isPresent()) {
            log.debug("Account '{}' already exists; open is a no-op", cmd.accountId());
            return;
        }
        var periodId = closingBooksPolicy.currentPeriodId(clock);
        accounts.openNewAccount(cmd.accountId(),
                                context -> new Account(context.streamAggregateId(),
                                                       cmd.accountId(),
                                                       cmd.ownerId(),
                                                       cmd.currency(),
                                                       periodId,
                                                       OpeningBalances.empty(Money.of(BigDecimal.ZERO, cmd.currency()))));
    }
}
