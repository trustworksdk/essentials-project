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

package dk.trustworks.essentials.examples.trading.brokerage.events;

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The settlement has settled. Named {@code SettlementMarkedSettled} rather than {@code SettlementSettled} because the
 * class name is the persisted event type -- renaming it would orphan every event already stored under the old name.
 */
public record SettlementMarkedSettled(SettlementId settlementId) implements SettlementEvent {
    public SettlementMarkedSettled {
        requireNonNull(settlementId, "No settlementId provided");
    }
}
