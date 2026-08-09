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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * The {@code status} is always {@link TransferLifeCycleStatus#COMPLETED}, but it stays a record component so that it
 * remains part of the persisted JSON. Construction therefore goes through {@link #of(TransactionId)} rather than
 * through a second constructor — a record's canonical constructor is the one Jackson 3 binds the persisted properties
 * to, and adding a shorter one only invites it to pick the wrong creator.
 * <p>
 * Since the canonical constructor stays public and accepts any {@link TransferLifeCycleStatus}, the compact
 * constructor enforces the invariant the old hand-written constructor got for free by hardcoding the value.
 */
public record IntraBankMoneyTransferCompleted(TransactionId transactionId,
                                              TransferLifeCycleStatus status) implements IntraBankMoneyTransferEvent {
    public IntraBankMoneyTransferCompleted {
        requireNonNull(transactionId, "No transactionId provided");
        requireNonNull(status, "No status provided");
        requireTrue(status == TransferLifeCycleStatus.COMPLETED,
                    "status must be " + TransferLifeCycleStatus.COMPLETED + " but was " + status);
    }

    public static IntraBankMoneyTransferCompleted of(TransactionId transactionId) {
        return new IntraBankMoneyTransferCompleted(transactionId, TransferLifeCycleStatus.COMPLETED);
    }
}
