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

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * An operator corrected settled cash, with a reason: {@code amount} is signed. The only way cash appears or
 * disappears other than a deposit, a withdrawal or a trade.
 */
public record CashAdjusted(AccountGenerationId accountStreamId,
                           AccountId accountId,
                           AdjustmentId adjustmentId,
                           Money amount,
                           String reason,
                           String adjustedBy) implements AccountEvent {
    public CashAdjusted {
        requireNonNull(accountStreamId, "No accountStreamId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(adjustmentId, "No adjustmentId provided");
        requireNonNull(amount, "No amount provided");
        requireTrue(amount.signum() != 0, "amount must not be zero");
        requireNonNull(reason, "No reason provided");
        requireNonNull(adjustedBy, "No adjustedBy provided");
    }
}
