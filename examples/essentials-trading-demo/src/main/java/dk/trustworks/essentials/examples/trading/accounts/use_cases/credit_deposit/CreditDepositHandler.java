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

package dk.trustworks.essentials.examples.trading.accounts.use_cases.credit_deposit;

import dk.trustworks.essentials.examples.trading.accounts.aggregates.Accounts;
import dk.trustworks.essentials.examples.trading.accounts.config.AccountsProperties;
import dk.trustworks.essentials.examples.trading.accounts.types.BusinessDays;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of {@code accounts.credit_deposit}. Loads through {@code getAccountForMutation}, the
 * closing-books trigger, so the first command of a new business day rolls the account first.
 */
@Service
public class CreditDepositHandler extends AnnotatedCommandHandler {
    private final Accounts           accounts;
    private final AccountsProperties properties;

    public CreditDepositHandler(Accounts accounts, AccountsProperties properties) {
        this.accounts = requireNonNull(accounts, "No accounts provided");
        this.properties = requireNonNull(properties, "No properties provided");
    }

    @CmdHandler
    public void handle(CreditDeposit cmd) {
        requireNonNull(cmd, "No cmd provided");
        var account = accounts.getAccountForMutation(cmd.accountId());
        account.creditDeposit(cmd.depositId(), cmd.amount(), cmd.businessDate(), BusinessDays.add(cmd.businessDate(), properties.getDepositReturnWindowBusinessDays()));
    }
}
