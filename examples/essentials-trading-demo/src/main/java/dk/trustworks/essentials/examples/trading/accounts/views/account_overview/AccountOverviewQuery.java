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

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.types.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Reads {@code projection_account_overview} and derives the customer figures with the same formulas as the aggregate.
 */
@Service
public class AccountOverviewQuery {
    private static final String SELECT = """
            SELECT account_id, owner_id, currency, generation, period_id, settled, pending_in, pending_out, held, uncleared, realized_pnl
            FROM projection_account_overview
            """;

    private static final RowMapper<AccountOverview> ROW = (rs, rowNum) -> {
        var currency   = CurrencyCode.of(rs.getString("currency"));
        var settled    = rs.getBigDecimal("settled");
        var pendingIn  = rs.getBigDecimal("pending_in");
        var pendingOut = rs.getBigDecimal("pending_out");
        var held       = rs.getBigDecimal("held");
        var uncleared  = rs.getBigDecimal("uncleared");
        var withdrawable = settled.subtract(pendingOut).subtract(held).subtract(uncleared).max(BigDecimal.ZERO);
        return new AccountOverview(AccountId.of(rs.getString("account_id")),
                                   UserId.of(rs.getString("owner_id")),
                                   rs.getLong("generation"),
                                   PeriodId.of(rs.getString("period_id")),
                                   Money.of(settled, currency),
                                   Money.of(pendingIn, currency),
                                   Money.of(pendingOut, currency),
                                   Money.of(held, currency),
                                   Money.of(uncleared, currency),
                                   Money.of(rs.getBigDecimal("realized_pnl"), currency),
                                   Money.of(settled.add(pendingIn).subtract(pendingOut), currency),
                                   Money.of(settled.add(pendingIn).subtract(pendingOut).subtract(held), currency),
                                   Money.of(withdrawable, currency),
                                   settled.signum() < 0);
    };

    private final JdbcTemplate jdbcTemplate;

    public AccountOverviewQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    public Optional<AccountOverview> findAccount(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return jdbcTemplate.query(SELECT + " WHERE account_id = ?", ROW, accountId.toString()).stream().findFirst();
    }

    public List<AccountOverview> accountsOwnedBy(UserId ownerId) {
        requireNonNull(ownerId, "No ownerId provided");
        return jdbcTemplate.query(SELECT + " WHERE owner_id = ? ORDER BY account_id", ROW, ownerId.toString());
    }
}
