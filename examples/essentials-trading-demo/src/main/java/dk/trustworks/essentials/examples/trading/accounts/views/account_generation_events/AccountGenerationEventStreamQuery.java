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

package dk.trustworks.essentials.examples.trading.accounts.views.account_generation_events;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateLifecycleApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiClosingBooksGenerationEventStream;
import dk.trustworks.essentials.examples.trading.accounts.aggregates.Accounts;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The one query of the {@code accounts.account_generation_events} slice.
 * <p>
 * This slice owns no table. What it serves is the raw event stream of one sealed books generation, read straight from
 * the event store through {@link AggregateLifecycleApi} — framework lifecycle data, in the framework's own
 * {@link ApiClosingBooksGenerationEventStream} shape. There is nothing to project and nothing to map: a read model
 * would be a copy of the event store with no question of its own to answer.
 */
@Service
public class AccountGenerationEventStreamQuery {
    /**
     * The principal the demo's admin surface acts as. The demo has no authentication; a real deployment would pass the
     * authenticated caller.
     */
    private static final String DEMO_ADMIN_PRINCIPAL = "demo-admin";

    private final AggregateLifecycleApi aggregateLifecycleApi;

    public AccountGenerationEventStreamQuery(AggregateLifecycleApi aggregateLifecycleApi) {
        this.aggregateLifecycleApi = requireNonNull(aggregateLifecycleApi, "No aggregateLifecycleApi provided");
    }

    /**
     * @throws IllegalStateException if the account has no such generation — the same message, and the same failure
     *                               mode, as before this was a slice
     */
    @Transactional(readOnly = true)
    public ApiClosingBooksGenerationEventStream generationEventStream(AccountId accountId, long generation) {
        requireNonNull(accountId, "No accountId provided");
        return aggregateLifecycleApi.findClosingBooksGenerationEventStream(DEMO_ADMIN_PRINCIPAL,
                                                                          Accounts.AGGREGATE_TYPE,
                                                                          accountId.toString(),
                                                                          generation)
                                    .orElseThrow(() -> new IllegalStateException("Couldn't resolve generation " + generation + " for account " + accountId));
    }
}
