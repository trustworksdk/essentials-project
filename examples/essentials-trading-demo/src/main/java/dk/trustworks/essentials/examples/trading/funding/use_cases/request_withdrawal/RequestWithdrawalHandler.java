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

package dk.trustworks.essentials.examples.trading.funding.use_cases.request_withdrawal;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.hold_funds.HoldFunds;
import dk.trustworks.essentials.examples.trading.funding.aggregates.*;
import dk.trustworks.essentials.examples.trading.funding.types.FundingRuleViolation;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of {@code funding.request_withdrawal}: a withdrawal needs a linked bank account (design Q-2),
 * and takes the one linked at the moment it is requested.
 * <p>
 * The withdrawal and its hold are one decision, as an order and its hold are (phase 3 Q-1): the hold is requested with
 * the ledger's own {@link HoldFunds} on the bus, which joins this unit of work, so a {@code LedgerRuleViolation} - more
 * than is withdrawable, a restricted account - rolls the withdrawal back with it, and nothing reaches the gateway. The
 * gateway checks no balance of its own. The hold's id is the withdrawal's id.
 */
@Service
public class RequestWithdrawalHandler extends AnnotatedCommandHandler {
    private final Withdrawals          withdrawals;
    private final BankAccountLinks links;
    private final CommandBus       commandBus;

    public RequestWithdrawalHandler(Withdrawals withdrawals, BankAccountLinks links, CommandBus commandBus) {
        this.withdrawals = requireNonNull(withdrawals, "No withdrawals provided");
        this.links = requireNonNull(links, "No links provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @CmdHandler
    public void handle(RequestWithdrawal cmd) {
        requireNonNull(cmd, "No cmd provided");
        if (withdrawals.find(cmd.withdrawalId()).isPresent()) {
            return;
        }
        var bankAccount = links.find(cmd.accountId())
                               .map(BankAccountLink::bankAccount)
                               .orElseThrow(() -> new FundingRuleViolation("Account '" + cmd.accountId() + "' has no linked bank account; link one first"));
        withdrawals.saveNew(new Withdrawal(cmd.withdrawalId(), cmd.accountId(), cmd.amount(), bankAccount));
        commandBus.send(new HoldFunds(cmd.accountId(), HoldId.of(cmd.withdrawalId()), HoldPurpose.WITHDRAWAL, cmd.amount()));
    }
}
