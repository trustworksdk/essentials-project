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

package dk.trustworks.essentials.examples.trading.brokerage.views.account_statement;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.GenerationState;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;

import java.time.OffsetDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One books generation of a trading account, as it appears inside an {@link AccountOverview}.
 * <p>
 * This is <b>not</b> a second read model. It is the framework's own closing-books lifecycle metadata — read through
 * {@code AggregateLifecycleApi}, owned by the event store, and projected by nothing in this application. That is why
 * the overview that carries it belongs in this slice rather than in one of its own: there is no model here for a
 * second slice to own.
 *
 * @param generation        the generation number; {@code 1} is the account's first
 * @param streamAggregateId the id the generation's own event stream is keyed on, {@code <accountId>#<generation>}
 * @param state             OPEN for the generation currently taking events, CLOSED for a sealed one
 * @param closedAt          {@code null} while the generation is still open
 */
public record AccountGeneration(long generation,
                                TradingAccountGenerationId streamAggregateId,
                                GenerationState state,
                                OffsetDateTime openedAt,
                                OffsetDateTime closedAt) {
    public AccountGeneration {
        requireNonNull(streamAggregateId, "No streamAggregateId provided");
        requireNonNull(state, "No state provided");
    }
}
