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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_settlement;

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Request settlement of an executed trade, naming the {@link SettlementId} the resulting {@code Settlement} will be
 * keyed on.
 *
 * <p>Both components are non-null. That is why {@code RequestSettlementAPI} does <em>not</em> take this record as a
 * {@code @RequestBody}: the trade id lives in the path, so a body carrying only a settlement id would have to push a
 * {@code null} through this constructor. The endpoint takes the settlement id as a request parameter and assembles
 * the command instead, which keeps the command fully non-null and leaves exactly one constructor here.
 *
 * <p>Requesting settlement before the trade is executed is rejected by {@code Trade}; re-requesting it is a no-op.
 */
public record RequestSettlement(TradeId tradeId,
                                SettlementId settlementId) {
    public RequestSettlement {
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(settlementId, "No settlementId provided");
    }
}
