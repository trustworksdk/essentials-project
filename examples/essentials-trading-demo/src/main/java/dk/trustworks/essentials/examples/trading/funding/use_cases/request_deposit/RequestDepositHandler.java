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

package dk.trustworks.essentials.examples.trading.funding.use_cases.request_deposit;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.funding.aggregates.*;
import dk.trustworks.essentials.examples.trading.funding.types.FundingRuleViolation;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of {@code funding.request_deposit}: a deposit needs a linked bank account (design Q-2),
 * and takes the one linked at the moment it is requested.
 */
@Service
public class RequestDepositHandler extends AnnotatedCommandHandler {
    private final Deposits          deposits;
    private final BankAccountLinks links;

    public RequestDepositHandler(Deposits deposits, BankAccountLinks links) {
        this.deposits = requireNonNull(deposits, "No deposits provided");
        this.links = requireNonNull(links, "No links provided");
    }

    @CmdHandler
    public void handle(RequestDeposit cmd) {
        requireNonNull(cmd, "No cmd provided");
        if (deposits.find(cmd.depositId()).isPresent()) {
            return;
        }
        var bankAccount = links.find(cmd.accountId())
                               .map(BankAccountLink::bankAccount)
                               .orElseThrow(() -> new FundingRuleViolation("Account '" + cmd.accountId() + "' has no linked bank account; link one first"));
        deposits.saveNew(new Deposit(cmd.depositId(), cmd.accountId(), cmd.amount(), bankAccount));
    }
}
