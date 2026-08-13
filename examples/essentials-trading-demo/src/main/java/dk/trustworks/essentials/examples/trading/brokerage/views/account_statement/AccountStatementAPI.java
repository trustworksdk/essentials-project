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

import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The read API of the {@code brokerage.account_statement} view slice, and of no other (§R2).
 * <p>
 * Two query methods, one slice: both interrogate the read model <em>this</em> slice owns. The second additionally
 * carries the framework's closing-books generations, which no slice owns because nothing projects them.
 * <p>
 * There is no class-level {@code @RequestMapping}: the two endpoints sit under different admin prefixes and both paths
 * are load-bearing — the admin UI links to {@code /api/admin/projections/account-statements} by hand.
 * <p>
 * {@code @PathVariable TradingAccountId} binds because {@code config/TradingDemoWebConfiguration} imports
 * {@code EssentialsWebMvcConfigurer}. Without it this is an HTTP <b>500</b>, not a 400.
 * <p>
 * Reads are eventually consistent: {@link AccountStatementProjection} is an asynchronous {@code ViewEventProcessor},
 * so a balance fetched immediately after a deposit may still be the pre-deposit figure.
 */
@RestController
public class AccountStatementAPI {
    private final AccountStatementQuery query;

    public AccountStatementAPI(AccountStatementQuery accountStatementQuery) {
        this.query = requireNonNull(accountStatementQuery, "No accountStatementQuery provided");
    }

    @GetMapping("/api/admin/projections/account-statements")
    public List<AccountStatement> accountStatements() {
        return query.accountStatements();
    }

    /**
     * 404 here means the account's statement has not been projected yet, which is a real state for an asynchronous
     * projection. An account the event store has never heard of fails earlier, inside the query.
     */
    @GetMapping("/api/admin/trading-accounts/{accountId}")
    public ResponseEntity<AccountOverview> accountOverview(@PathVariable TradingAccountId accountId) {
        return query.findAccountOverview(accountId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
