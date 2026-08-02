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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.events;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.TransactionId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.ValueDate;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.AccountId;
import dk.trustworks.essentials.types.Amount;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class AccountDeposited extends AccountEvent {
    public final Amount        depositedAmount;
    public final ValueDate     valueDate;
    public final TransactionId transactionId;

    public AccountDeposited(AccountId accountId,
                 Amount depositedAmount,
                 ValueDate valueDate,
                 TransactionId transactionId) {
        super(accountId);
        this.depositedAmount = requireNonNull(depositedAmount, "No depositedAmount provided");
        this.valueDate = requireNonNull(valueDate, "No valueDate provided");
        this.transactionId = requireNonNull(transactionId, "No transactionId provided");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!super.equals(o)) return false;
        var that = (AccountDeposited) o;
        return Objects.equals(depositedAmount, that.depositedAmount)
                && Objects.equals(valueDate, that.valueDate)
                && Objects.equals(transactionId, that.transactionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), depositedAmount, valueDate, transactionId);
    }

    @Override
    public String toString() {
        return "AccountDeposited(accountId=" + accountId +
                ", depositedAmount=" + depositedAmount +
                ", valueDate=" + valueDate +
                ", transactionId=" + transactionId + ")";
    }
}
