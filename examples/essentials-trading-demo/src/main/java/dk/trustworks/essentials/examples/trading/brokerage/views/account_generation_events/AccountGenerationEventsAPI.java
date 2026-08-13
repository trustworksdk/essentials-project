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

package dk.trustworks.essentials.examples.trading.brokerage.views.account_generation_events;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiClosingBooksGenerationEventStream;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The read API of the {@code brokerage.account_generation_events} view slice, and of no other (§R2).
 * <p>
 * The response body is the framework's own {@link ApiClosingBooksGenerationEventStream}. This slice owns no read
 * model, so there is nothing of ours to return and nothing to map it to.
 * <p>
 * {@code @PathVariable TradingAccountId} binds because {@code config/TradingDemoWebConfiguration} imports
 * {@code EssentialsWebMvcConfigurer}. Without it this is an HTTP <b>500</b>, not a 400.
 */
@RestController
@RequestMapping(path = "/api/admin/trading-accounts")
public class AccountGenerationEventsAPI {
    private final AccountGenerationEventsQuery query;

    public AccountGenerationEventsAPI(AccountGenerationEventsQuery accountGenerationEventsQuery) {
        this.query = requireNonNull(accountGenerationEventsQuery, "No accountGenerationEventsQuery provided");
    }

    @GetMapping("/{accountId}/generations/{generation}/events")
    public ApiClosingBooksGenerationEventStream generationEvents(@PathVariable TradingAccountId accountId,
                                                                 @PathVariable long generation) {
        return query.generationEventStream(accountId, generation);
    }
}
