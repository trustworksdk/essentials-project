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

package dk.trustworks.essentials.examples.trading.accounts.use_cases.roll_account_books;

import dk.trustworks.essentials.examples.trading.accounts.aggregates.Accounts;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Loads with {@code getAccount}, <b>not</b> {@code getAccountForMutation}: the latter is the policy's automatic roll,
 * and going through it would turn one requested roll into two.
 */
@Service
public class RollAccountBooksHandler extends AnnotatedCommandHandler {
    private final Accounts accounts;

    public RollAccountBooksHandler(Accounts accounts) {
        this.accounts = requireNonNull(accounts, "No accounts provided");
    }

    @CmdHandler
    public void handle(RollAccountBooks cmd) {
        requireNonNull(cmd, "No cmd provided");
        var account = accounts.getAccount(cmd.accountId());
        account.closeBooks(cmd.nextPeriodId());
        accounts.closeAndOpenNextGeneration(cmd.accountId(), account, cmd.nextPeriodId());
    }
}
