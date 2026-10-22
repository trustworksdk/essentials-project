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

package dk.trustworks.essentials.examples.trading.funding.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The bank account linked to a trading account (design Q-2): deposits come from it, withdrawals go only to it. One
 * stream per trading account; linking another account replaces it.
 */
public class BankAccountLink extends AggregateRoot<AccountId, BankAccountLinkEvent, BankAccountLink> {
    private BankAccount bankAccount;

    protected BankAccountLink() {
    }

    /**
     * Used for rehydration
     */
    public BankAccountLink(AccountId accountId) {
        super(accountId);
    }

    public BankAccountLink(AccountId accountId, BankAccount bankAccount) {
        super(accountId);
        link(bankAccount);
    }

    /**
     * Links {@code bankAccount}; the one already linked is a no-op.
     */
    public void link(BankAccount bankAccount) {
        requireNonNull(bankAccount, "No bankAccount provided");
        if (bankAccount.equals(this.bankAccount)) {
            return;
        }
        apply(new BankAccountLinked(aggregateId(), bankAccount));
    }

    public BankAccount bankAccount() {
        return bankAccount;
    }

    @EventHandler
    private void on(BankAccountLinked e) {
        bankAccount = e.bankAccount();
    }
}
