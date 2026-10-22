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

package dk.trustworks.essentials.examples.trading.accounts.views.account_valuation;

import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverviewQuery;
import dk.trustworks.essentials.examples.trading.accounts.views.positions.PositionsQuery;
import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import dk.trustworks.essentials.examples.trading.market_data.types.ClosingPrices;
import dk.trustworks.essentials.types.Money;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An account's end-of-day valuation (design phase 4 §6, Q-4): the current positions at the exchange's latest closing
 * prices, read through {@code market_data}'s published {@link ClosingPrices} seam, worked out when asked. Not kept per
 * business day: positions are current state, so a past day's valuation is not reproduced here (statements, later).
 * Somebody else's account answers {@code 404}, as the account views do.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountValuationAPI {
    private final AccountOverviewQuery accounts;
    private final PositionsQuery       positions;
    private final ClosingPrices        closingPrices;

    public AccountValuationAPI(AccountOverviewQuery accounts, PositionsQuery positions, ClosingPrices closingPrices) {
        this.accounts = requireNonNull(accounts, "No accounts provided");
        this.positions = requireNonNull(positions, "No positions provided");
        this.closingPrices = requireNonNull(closingPrices, "No closingPrices provided");
    }

    @GetMapping("/{accountId}/valuation")
    public ResponseEntity<AccountValuation> valuation(@PathVariable AccountId accountId, @AuthenticationPrincipal AuthenticatedUser caller) {
        var account = accounts.findAccount(accountId).filter(found -> caller.isOperator() || found.ownerId().equals(caller.userId()));
        if (account.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var cash         = account.get().cashBalance();
        var businessDate = closingPrices.latestBusinessDate().orElse(null);
        var valued = positions.positions(accountId).stream()
                              .filter(position -> position.held().isPositive())
                              .map(position -> {
                                  var price = businessDate == null ? java.util.Optional.<ClosingPrices.ClosingPrice>empty() : closingPrices.latestOnOrBefore(position.instrumentId(), businessDate);
                                  var value = price.map(p -> Money.of(p.price().getAmount().value().multiply(BigDecimal.valueOf(position.held().value())), cash.getCurrency()))
                                                   .orElse(Money.of(BigDecimal.ZERO, cash.getCurrency()));
                                  return new AccountValuation.ValuedPosition(position.instrumentId(), position.held(),
                                                                             price.map(ClosingPrices.ClosingPrice::price).orElse(null),
                                                                             price.map(ClosingPrices.ClosingPrice::businessDate).orElse(null),
                                                                             value);
                              })
                              .toList();
        var marketValue = valued.stream().map(AccountValuation.ValuedPosition::value).reduce(Money.of(BigDecimal.ZERO, cash.getCurrency()), Money::add);
        return ResponseEntity.ok(new AccountValuation(accountId, businessDate, cash, marketValue, cash.add(marketValue), valued));
    }
}
