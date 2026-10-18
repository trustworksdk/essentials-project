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

package dk.trustworks.essentials.examples.trading.accounts.use_cases.reserve_shares;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Set shares aside for an open sell order. Refused beyond the sellable quantity.
 * <p>
 * Both the command and the request body of {@code POST /api/admin/accounts/{accountId}/share-reservations}.
 */
public record ReserveShares(AccountId accountId,
                            ReservationId reservationId,
                            InstrumentId instrumentId,
                            Quantity quantity) {
    public ReserveShares {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(reservationId, "No reservationId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(quantity, "No quantity provided");
    }
}
