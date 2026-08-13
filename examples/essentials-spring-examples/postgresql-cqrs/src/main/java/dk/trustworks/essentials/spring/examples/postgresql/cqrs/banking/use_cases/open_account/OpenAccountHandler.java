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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.use_cases.open_account;

import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.Account;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.Accounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code banking.open_account} slice — one command, one handler
 * (rules/slice-design.md §R1).
 * <p>
 * Opening is idempotent: re-sending {@code OpenAccount} for an existing account is a no-op rather than an error,
 * which matches how the sibling command slices behave under the at-least-once delivery of the command bus.
 */
@Service
public class OpenAccountHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(OpenAccountHandler.class);

    private final Accounts accounts;

    public OpenAccountHandler(Accounts accounts) {
        this.accounts = requireNonNull(accounts, "No accounts provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(OpenAccount cmd) {
        requireNonNull(cmd, "No cmd provided");
        if (accounts.isAccountMissing(cmd.accountId())) {
            log.debug("===> Opening new Account '{}'", cmd.accountId());
            accounts.openNewAccount(new Account(cmd.accountId(), cmd.accountNumber()));
        }
    }
}
