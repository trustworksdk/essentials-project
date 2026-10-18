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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Service
public class AccountActivityQuery {
    private final JdbcTemplate jdbcTemplate;

    public AccountActivityQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    /**
     * The latest {@code limit} entries, newest first.
     */
    public List<ActivityEntry> latest(AccountId accountId, int limit) {
        requireNonNull(accountId, "No accountId provided");
        return jdbcTemplate.query("""
                                          SELECT generation, event_order, kind, reference, description, cash_effect
                                          FROM projection_account_activity
                                          WHERE account_id = ?
                                          ORDER BY generation DESC, event_order DESC
                                          LIMIT ?
                                          """,
                                  (rs, rowNum) -> new ActivityEntry(accountId,
                                                                    rs.getLong("generation"),
                                                                    rs.getLong("event_order"),
                                                                    rs.getString("kind"),
                                                                    rs.getString("reference"),
                                                                    rs.getString("description"),
                                                                    rs.getBigDecimal("cash_effect")),
                                  accountId.toString(),
                                  Math.clamp(limit, 1, 1000));
    }

    /**
     * How many statement lines exist for accounts whose id starts with {@code accountIdPrefix}. For the demo harness's
     * ledger load scenario, which uses it to see when this view has caught up with a run's commands.
     */
    public long countEntriesForAccountsStartingWith(String accountIdPrefix) {
        requireNonNull(accountIdPrefix, "No accountIdPrefix provided");
        var count = jdbcTemplate.queryForObject("SELECT count(*) FROM projection_account_activity WHERE account_id LIKE ?",
                                                Long.class,
                                                accountIdPrefix.replace("%", "\\%").replace("_", "\\_") + "%");
        return count == null ? 0 : count;
    }
}
