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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The bank reversed a completed deposit. Settled cash goes down by {@code amount}, possibly below zero, which restricts
 * the account (design §3.3). {@code unclearedReleased} is what left the uncleared bucket — the amount if the deposit
 * was still uncleared, zero if it had cleared.
 */
public record DepositReturned(AccountGenerationId accountStreamId,
                              AccountId accountId,
                              DepositId depositId,
                              Money amount,
                              Money unclearedReleased) implements AccountEvent {
    public DepositReturned {
        requireNonNull(accountStreamId, "No accountStreamId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(depositId, "No depositId provided");
        AccountEvent.requirePositive(amount, "amount");
        requireNonNull(unclearedReleased, "No unclearedReleased provided");
    }
}
