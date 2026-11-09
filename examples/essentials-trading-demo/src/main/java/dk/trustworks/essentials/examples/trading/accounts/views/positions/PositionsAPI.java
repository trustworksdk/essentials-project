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

package dk.trustworks.essentials.examples.trading.accounts.views.positions;

import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverviewQuery;
import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An account's positions, for its owner or an operator.
 * <p>
 * Ownership is read from {@code account_overview}'s query. That is a read of another view slice's model, which §R2
 * forbids for a <em>decision</em>; here it is an authorization lookup with no write behind it, and duplicating the owner
 * into this projection would be a second copy of the same fact. Revisit if a positions-only owner check is ever needed.
 */
@RestController
@RequestMapping("/api/accounts")
public class PositionsAPI {
    private final PositionsQuery       query;
    private final AccountOverviewQuery accounts;

    public PositionsAPI(PositionsQuery query, AccountOverviewQuery accounts) {
        this.query = requireNonNull(query, "No query provided");
        this.accounts = requireNonNull(accounts, "No accounts provided");
    }

    @GetMapping("/{accountId}/positions")
    public ResponseEntity<List<PositionView>> positions(@PathVariable AccountId accountId, @AuthenticationPrincipal AuthenticatedUser caller) {
        var visible = accounts.findAccount(accountId).filter(account -> caller.isOperator() || account.ownerId().equals(caller.userId())).isPresent();
        return visible ? ResponseEntity.ok(query.positions(accountId)) : ResponseEntity.notFound().build();
    }
}
