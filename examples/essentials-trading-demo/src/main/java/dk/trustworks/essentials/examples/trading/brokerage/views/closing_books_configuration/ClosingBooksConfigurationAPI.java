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

package dk.trustworks.essentials.examples.trading.brokerage.views.closing_books_configuration;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccountClosingBooksPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The read API of the {@code brokerage.closing_books_configuration} view slice, and of no other (§R2).
 * <p>
 * One query, over in-memory configuration rather than a projected table: the closing-books policy is runtime state the
 * demo lets an admin retune, and it is not event-sourced. There is nothing to project, so this slice owns no read
 * model — only the rendering of one.
 * <p>
 * <b>The mutating side is not here.</b> {@code POST /api/admin/trading-accounts/closing-books} belongs to
 * {@code use_cases/update_closing_books_settings}. A view slice never writes, and the two live at the same path
 * precisely because they are the read and the write of the same thing.
 * <p>
 * {@code settings()} is read <em>once</em> and passed whole. Reading the policy field-by-field could interleave with
 * an update and report a combination that was never in force — which is what the immutable settings record exists to
 * prevent.
 */
@RestController
@RequestMapping(path = "/api/admin/trading-accounts")
public class ClosingBooksConfigurationAPI {
    private final TradingAccountClosingBooksPolicy closingBooksPolicy;

    public ClosingBooksConfigurationAPI(TradingAccountClosingBooksPolicy closingBooksPolicy) {
        this.closingBooksPolicy = requireNonNull(closingBooksPolicy, "No closingBooksPolicy provided");
    }

    @GetMapping("/closing-books")
    public ClosingBooksConfiguration closingBooksConfiguration() {
        return ClosingBooksConfiguration.from(closingBooksPolicy.settings(),
                                              closingBooksPolicy.description());
    }
}
