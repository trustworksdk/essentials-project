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

package dk.trustworks.essentials.examples.trading.accounts.events;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.types.Money;

/**
 * The closed set of events an {@code Account} emits (design §4). Every event names both the generation's stream id
 * and the logical account id, and carries its own effect, so a projection never needs the aggregate's private state.
 */
public sealed interface AccountEvent permits AccountOpened, CashAdjusted, DepositCredited, DepositCleared, DepositReturned,
                                             FundsHeld, FundsReleased, CashWithdrawn, WithdrawalReturnCredited, SharesReserved, SharesReleased,
                                             TradeBooked, TradeSettled, TradeBookingReversed, AccountBooksClosed,
                                             UnknownReferenceIgnored {

    AccountGenerationId accountStreamId();

    AccountId accountId();

    static Money requirePositive(Money amount, String name) {
        if (amount == null) {
            throw new IllegalArgumentException("No " + name + " provided");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be > 0, was " + amount);
        }
        return amount;
    }

    static Quantity requirePositive(Quantity quantity, String name) {
        if (quantity == null) {
            throw new IllegalArgumentException("No " + name + " provided");
        }
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException(name + " must be > 0, was " + quantity);
        }
        return quantity;
    }
}
