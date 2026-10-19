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

package dk.trustworks.essentials.examples.trading.orders.views.orders;

import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import dk.trustworks.essentials.examples.trading.orders.types.OrderId;
import dk.trustworks.essentials.examples.trading.orders.views.account_owners.AccountOwnersQuery;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An account's orders, newest first. Somebody else's account answers {@code 404}.
 */
@RestController
@RequestMapping("/api/accounts")
public class OrdersAPI {
    private final OrdersQuery        query;
    private final AccountOwnersQuery accountOwners;

    public OrdersAPI(OrdersQuery query, AccountOwnersQuery accountOwners) {
        this.query = requireNonNull(query, "No query provided");
        this.accountOwners = requireNonNull(accountOwners, "No accountOwners provided");
    }

    @GetMapping("/{accountId}/orders")
    public ResponseEntity<List<OrderView>> orders(@PathVariable AccountId accountId, @AuthenticationPrincipal AuthenticatedUser caller) {
        if (!accountOwners.mayTradeOn(accountId, caller)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(query.ordersOf(accountId));
    }

    @GetMapping("/{accountId}/orders/{orderId}")
    public ResponseEntity<OrderView> order(@PathVariable AccountId accountId, @PathVariable OrderId orderId, @AuthenticationPrincipal AuthenticatedUser caller) {
        if (!accountOwners.mayTradeOn(accountId, caller)) {
            return ResponseEntity.notFound().build();
        }
        return query.findOrder(accountId, orderId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
