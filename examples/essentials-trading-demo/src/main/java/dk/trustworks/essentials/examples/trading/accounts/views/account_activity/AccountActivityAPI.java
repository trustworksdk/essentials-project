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

package dk.trustworks.essentials.examples.trading.accounts.views.account_activity;

import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverviewQuery;
import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An account's statement, for its owner or an operator. Ownership via {@code account_overview}'s query, as in
 * {@code PositionsAPI}.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountActivityAPI {
    private final AccountActivityQuery query;
    private final AccountOverviewQuery accounts;

    public AccountActivityAPI(AccountActivityQuery query, AccountOverviewQuery accounts) {
        this.query = requireNonNull(query, "No query provided");
        this.accounts = requireNonNull(accounts, "No accounts provided");
    }

    @GetMapping("/{accountId}/activity")
    public ResponseEntity<List<ActivityEntry>> activity(@PathVariable AccountId accountId,
                                                        @RequestParam(defaultValue = "100") int limit,
                                                        @AuthenticationPrincipal AuthenticatedUser caller) {
        var visible = accounts.findAccount(accountId).filter(account -> caller.isOperator() || account.ownerId().equals(caller.userId())).isPresent();
        return visible ? ResponseEntity.ok(query.latest(accountId, limit)) : ResponseEntity.notFound().build();
    }
}
