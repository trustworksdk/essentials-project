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

package dk.trustworks.essentials.examples.trading.accounts.types;

import dk.trustworks.essentials.types.Money;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Everything a new generation starts from (design §6): the whole open state of the previous one. Empty for a brand new
 * account. Carrying the open items is also what keeps them idempotent across a roll (design §5).
 */
public record OpeningBalances(Money settledCash,
                              Money pendingCashIn,
                              Money pendingCashOut,
                              List<Hold> holds,
                              List<UnclearedDeposit> unclearedDeposits,
                              List<OpenTrade> openTrades,
                              List<PositionBalance> positions,
                              List<Reservation> reservations) {
    public OpeningBalances {
        requireNonNull(settledCash, "No settledCash provided");
        requireNonNull(pendingCashIn, "No pendingCashIn provided");
        requireNonNull(pendingCashOut, "No pendingCashOut provided");
        holds = List.copyOf(requireNonNull(holds, "No holds provided"));
        unclearedDeposits = List.copyOf(requireNonNull(unclearedDeposits, "No unclearedDeposits provided"));
        openTrades = List.copyOf(requireNonNull(openTrades, "No openTrades provided"));
        positions = List.copyOf(requireNonNull(positions, "No positions provided"));
        reservations = List.copyOf(requireNonNull(reservations, "No reservations provided"));
    }

    public static OpeningBalances empty(Money zero) {
        return new OpeningBalances(zero, zero, zero, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
