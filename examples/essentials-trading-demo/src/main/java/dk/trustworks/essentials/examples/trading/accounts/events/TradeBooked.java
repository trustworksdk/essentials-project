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
 * An execution is booked as pending: a buy owes {@code trade.grossAmount} at settlement and will receive the shares, a
 * sell the reverse. Carries what the booking decided, so nothing downstream recomputes it:
 * <ul>
 *     <li>{@code holdId}/{@code holdConsumed} — the part of a buy order's hold this fill used up (zero, and no hold id,
 *     for a sell or a buy without a hold);</li>
 *     <li>{@code reservationId}/{@code reservationConsumed} — likewise for a sell order's share reservation;</li>
 *     <li>{@code trade.costBasisChange}, {@code trade.realizedPnl} — the average-cost bookkeeping (design Q-5).</li>
 * </ul>
 * {@code holdId} and {@code reservationId} are null when there is none.
 */
public record TradeBooked(AccountGenerationId accountStreamId,
                          AccountId accountId,
                          OpenTrade trade,
                          Money price,
                          HoldId holdId,
                          Money holdConsumed,
                          ReservationId reservationId,
                          Quantity reservationConsumed) implements AccountEvent {
    public TradeBooked {
        requireNonNull(accountStreamId, "No accountStreamId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(trade, "No trade provided");
        AccountEvent.requirePositive(price, "price");
        requireNonNull(holdConsumed, "No holdConsumed provided");
        requireNonNull(reservationConsumed, "No reservationConsumed provided");
    }
}
