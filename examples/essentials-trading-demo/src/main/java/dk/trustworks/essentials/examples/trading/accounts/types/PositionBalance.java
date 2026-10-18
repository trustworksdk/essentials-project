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

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Money;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One instrument's shares in an account. Reservations for open sell orders are kept apart, in {@link Reservation}s.
 * {@code costBasis} is the total cost of the shares held (settled + pendingIn − pendingOut), average-cost method.
 */
public record PositionBalance(InstrumentId instrumentId,
                              Quantity settled,
                              Quantity pendingIn,
                              Quantity pendingOut,
                              Money costBasis) {
    public PositionBalance {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(settled, "No settled provided");
        requireNonNull(pendingIn, "No pendingIn provided");
        requireNonNull(pendingOut, "No pendingOut provided");
        requireNonNull(costBasis, "No costBasis provided");
    }

    public Quantity held() {
        return settled.plus(pendingIn).minus(pendingOut);
    }

    public boolean isEmpty() {
        return settled.isZero() && pendingIn.isZero() && pendingOut.isZero();
    }
}
