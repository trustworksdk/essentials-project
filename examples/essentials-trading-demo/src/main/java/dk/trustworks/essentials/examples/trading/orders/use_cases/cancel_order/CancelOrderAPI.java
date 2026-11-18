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

package dk.trustworks.essentials.examples.trading.orders.use_cases.cancel_order;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import dk.trustworks.essentials.examples.trading.orders.types.OrderId;
import dk.trustworks.essentials.examples.trading.orders.views.account_owners.AccountOwnersQuery;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code 202}: the request is recorded, the cancellation is the exchange's to confirm. A closed order answers {@code 422}.
 */
@RestController
@RequestMapping("/api/accounts")
public class CancelOrderAPI {
    private static final int MAX_ATTEMPTS = 5;

    private final CommandBus         commandBus;
    private final AccountOwnersQuery accountOwners;

    public CancelOrderAPI(CommandBus commandBus, AccountOwnersQuery accountOwners) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.accountOwners = requireNonNull(accountOwners, "No accountOwners provided");
    }

    @PostMapping("/{accountId}/orders/{orderId}/cancellation")
    public ResponseEntity<Void> cancel(@PathVariable AccountId accountId,
                                       @PathVariable OrderId orderId,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        if (!accountOwners.mayTradeOn(accountId, caller)) {
            return ResponseEntity.notFound().build();
        }
        sendRetryingConflicts(new CancelOrder(accountId, orderId));
        return ResponseEntity.accepted().build();
    }

    /**
     * Two writers on one account (or order) stream: the loser's unit of work fails with an optimistic-append conflict
     * and is simply decided again against the new state. Persistent contention answers {@code 409}.
     */
    private void sendRetryingConflicts(Object cmd) {
        for (var attempt = 1; ; attempt++) {
            try {
                commandBus.send(cmd);
                return;
            } catch (RuntimeException e) {
                if (!isConflict(e)) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Too much contention; try again");
                }
            }
        }
    }

    private static boolean isConflict(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticAppendToStreamException) {
                return true;
            }
        }
        return false;
    }
}
