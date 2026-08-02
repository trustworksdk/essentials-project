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
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.transactions.intrabank.TransferLifeCycleStatus;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class IntraBankMoneyTransferStatusChanged extends IntraBankMoneyTransferEvent {
    public final TransferLifeCycleStatus status;

    public IntraBankMoneyTransferStatusChanged(TransactionId transactionId,
                                               TransferLifeCycleStatus status) {
        super(transactionId);
        this.status = requireNonNull(status, "No status provided");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!super.equals(o)) return false;
        var that = (IntraBankMoneyTransferStatusChanged) o;
        return status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), status);
    }

    @Override
    public String toString() {
        return "IntraBankMoneyTransferStatusChanged(transactionId=" + transactionId + ", status=" + status + ")";
    }
}
