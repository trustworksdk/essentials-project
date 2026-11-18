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

package dk.trustworks.essentials.examples.trading.accounts.views.account_overview;

import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The caller's own accounts; an operator sees any account. Somebody else's account answers {@code 404}, never
 * {@code 403}, so its existence is not revealed.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountOverviewAPI {
    private final AccountOverviewQuery query;

    public AccountOverviewAPI(AccountOverviewQuery query) {
        this.query = requireNonNull(query, "No query provided");
    }

    @GetMapping
    public List<AccountOverview> myAccounts(@AuthenticationPrincipal AuthenticatedUser caller) {
        return query.accountsOwnedBy(caller.userId());
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountOverview> account(@PathVariable AccountId accountId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return query.findAccount(accountId)
                    .filter(account -> caller.isOperator() || account.ownerId().equals(caller.userId()))
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
