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

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A completed deposit: settled cash up, and uncleared until {@code clearsOn} (the return window, applied when the
 * deposit was credited, so a later change of window affects new deposits only).
 */
public record DepositCredited(AccountGenerationId accountStreamId,
                              AccountId accountId,
                              DepositId depositId,
                              Money amount,
                              LocalDate businessDate,
                              LocalDate clearsOn) implements AccountEvent {
    public DepositCredited {
        requireNonNull(accountStreamId, "No accountStreamId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(depositId, "No depositId provided");
        AccountEvent.requirePositive(amount, "amount");
        requireNonNull(businessDate, "No businessDate provided");
        requireNonNull(clearsOn, "No clearsOn provided");
    }
}
