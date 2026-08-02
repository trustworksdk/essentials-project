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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.commands;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.TransactionId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.AccountId;
import dk.trustworks.essentials.types.Amount;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class RequestIntraBankMoneyTransfer {
    public final TransactionId transactionId;
    public final AccountId     fromAccount;
    public final AccountId     toAccount;
    public final Amount        amount;

    public RequestIntraBankMoneyTransfer(TransactionId transactionId,
                                         AccountId fromAccount,
                                         AccountId toAccount,
                                         Amount amount) {
        this.transactionId = requireNonNull(transactionId, "No transactionId provided");
        this.fromAccount = requireNonNull(fromAccount, "No fromAccount provided");
        this.toAccount = requireNonNull(toAccount, "No toAccount provided");
        this.amount = requireNonNull(amount, "No amount provided");
    }

    public TransactionId getTransactionId() {
        return transactionId;
    }

    public AccountId getFromAccount() {
        return fromAccount;
    }

    public AccountId getToAccount() {
        return toAccount;
    }

    public Amount getAmount() {
        return amount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RequestIntraBankMoneyTransfer that)) return false;
        return Objects.equals(transactionId, that.transactionId)
                && Objects.equals(fromAccount, that.fromAccount)
                && Objects.equals(toAccount, that.toAccount)
                && Objects.equals(amount, that.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, fromAccount, toAccount, amount);
    }

    @Override
    public String toString() {
        return "RequestIntraBankMoneyTransfer(transactionId=" + transactionId +
                ", fromAccount=" + fromAccount +
                ", toAccount=" + toAccount +
                ", amount=" + amount + ")";
    }
}
