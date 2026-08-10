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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransactionId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransferLifeCycleStatus;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A money transfer has advanced to the next stage of its lifecycle.
 *
 * <p>Emitted once per completed leg -- {@code FROM_ACCOUNT_WITHDRAWN}, then {@code TO_ACCOUNT_DEPOSITED} -- so the
 * transfer's stream records how far it got even if the process stalls partway. That is what makes the automation
 * resumable: the status is reconstructed from these events, not held in memory.
 */
public record IntraBankMoneyTransferStatusChanged(TransactionId transactionId,
                                                  TransferLifeCycleStatus status) implements IntraBankMoneyTransferEvent {
    public IntraBankMoneyTransferStatusChanged {
        requireNonNull(transactionId, "No transactionId provided");
        requireNonNull(status, "No status provided");
    }
}
