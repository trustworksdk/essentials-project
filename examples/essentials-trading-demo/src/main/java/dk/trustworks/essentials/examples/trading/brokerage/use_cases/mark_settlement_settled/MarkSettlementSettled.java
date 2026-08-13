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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.mark_settlement_settled;

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Record that the settlement itself has completed -- cash and instrument have changed hands.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and, when the endpoint builds it from its path variable,
 * the payload of {@code POST /api/admin/settlements/{settlementId}/settlement}.
 *
 * <p>Distinct from {@code MarkTradeSettled}, which records the same milestone on the other side of the boundary, the
 * {@code Trade} aggregate. Nothing writes both in one transaction.
 */
public record MarkSettlementSettled(SettlementId settlementId) {
    public MarkSettlementSettled {
        requireNonNull(settlementId, "No settlementId provided");
    }
}
