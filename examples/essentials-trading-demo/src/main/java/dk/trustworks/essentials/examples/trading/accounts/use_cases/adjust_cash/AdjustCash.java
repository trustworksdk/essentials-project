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

package dk.trustworks.essentials.examples.trading.accounts.use_cases.adjust_cash;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.types.Money;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An operator corrects settled cash, with a reason. {@code amount} is signed. {@code adjustedBy} is set by the API from the logged-in operator.
 * <p>
 * Both the command and the request body of {@code POST /api/admin/accounts/{accountId}/cash-adjustments}. Nullable: {@code adjustedBy}.
 */
public record AdjustCash(AccountId accountId,
                         AdjustmentId adjustmentId,
                         Money amount,
                         String reason,
                         String adjustedBy) {
    public AdjustCash {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(adjustmentId, "No adjustmentId provided");
        requireNonNull(amount, "No amount provided");
        requireNonNull(reason, "No reason provided");
    }
}
