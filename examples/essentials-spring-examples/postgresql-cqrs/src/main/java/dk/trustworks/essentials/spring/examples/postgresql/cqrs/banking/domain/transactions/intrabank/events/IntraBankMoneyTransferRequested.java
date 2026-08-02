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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.transactions.intrabank.events;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.TransactionId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.commands.RequestIntraBankMoneyTransfer;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.AccountId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.transactions.intrabank.TransferLifeCycleStatus;
import dk.trustworks.essentials.types.Amount;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class IntraBankMoneyTransferRequested extends IntraBankMoneyTransferEvent {
    public final AccountId               fromAccount;
    public final AccountId               toAccount;
    public final Amount                  amount;
    public final TransferLifeCycleStatus status;

    /**
     * Jackson 3 derives the JSON property names of an event from its constructor parameter names, so the persisted
     * form of an event has to be reachable through a constructor whose parameters are named exactly like the fields.
     * Convenience construction from a command therefore goes through
     * {@link #from(RequestIntraBankMoneyTransfer)} rather than through a second constructor.
     */
    public IntraBankMoneyTransferRequested(TransactionId transactionId,
                                           AccountId fromAccount,
                                           AccountId toAccount,
                                           Amount amount,
                                           TransferLifeCycleStatus status) {
        super(transactionId);
        this.fromAccount = requireNonNull(fromAccount, "No fromAccount provided");
        this.toAccount = requireNonNull(toAccount, "No toAccount provided");
        this.amount = requireNonNull(amount, "No amount provided");
        this.status = requireNonNull(status, "No status provided");
    }

    public static IntraBankMoneyTransferRequested from(RequestIntraBankMoneyTransfer cmd) {
        requireNonNull(cmd, "No cmd provided");
        return new IntraBankMoneyTransferRequested(cmd.transactionId,
                                                   cmd.fromAccount,
                                                   cmd.toAccount,
                                                   cmd.amount,
                                                   TransferLifeCycleStatus.REQUESTED);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!super.equals(o)) return false;
        var that = (IntraBankMoneyTransferRequested) o;
        return Objects.equals(fromAccount, that.fromAccount)
                && Objects.equals(toAccount, that.toAccount)
                && Objects.equals(amount, that.amount)
                && status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), fromAccount, toAccount, amount, status);
    }

    @Override
    public String toString() {
        return "IntraBankMoneyTransferRequested(transactionId=" + transactionId +
                ", fromAccount=" + fromAccount +
                ", toAccount=" + toAccount +
                ", amount=" + amount +
                ", status=" + status + ")";
    }
}
