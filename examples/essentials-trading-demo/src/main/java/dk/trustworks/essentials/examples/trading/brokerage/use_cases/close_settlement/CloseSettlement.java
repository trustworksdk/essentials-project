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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_settlement;

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Close a reconciled settlement -- the last step of its lifecycle, after which it accepts nothing further.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and, when the endpoint builds it from its path variable,
 * the payload of {@code POST /api/admin/settlements/{settlementId}/closure}.
 *
 * <p>Alone among the settlement steps this one is <em>not</em> idempotent, and deliberately so: {@code Settlement}
 * needs no "already closed" short-circuit because {@code assertOpen()} has already rejected the call by then.
 */
public record CloseSettlement(SettlementId settlementId) {
    public CloseSettlement {
        requireNonNull(settlementId, "No settlementId provided");
    }
}
