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

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.*;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Service
public class PositionsQuery {
    private final JdbcTemplate jdbcTemplate;

    public PositionsQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    /**
     * The account's positions, leaving out instruments with nothing held, pending or reserved.
     */
    public List<PositionView> positions(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return jdbcTemplate.query("""
                                          SELECT instrument_id, settled, pending_in, pending_out, reserved, cost_basis, currency
                                          FROM projection_positions
                                          WHERE account_id = ? AND (settled <> 0 OR pending_in <> 0 OR pending_out <> 0 OR reserved <> 0)
                                          ORDER BY instrument_id
                                          """,
                                  (rs, rowNum) -> {
                                      var currency = CurrencyCode.of(rs.getString("currency"));
                                      var held     = rs.getLong("settled") + rs.getLong("pending_in") - rs.getLong("pending_out");
                                      var cost     = rs.getBigDecimal("cost_basis");
                                      return new PositionView(accountId,
                                                              InstrumentId.of(rs.getString("instrument_id")),
                                                              Quantity.of(rs.getLong("settled")),
                                                              Quantity.of(rs.getLong("pending_in")),
                                                              Quantity.of(rs.getLong("pending_out")),
                                                              Quantity.of(held),
                                                              Quantity.of(rs.getLong("reserved")),
                                                              Quantity.of(held - rs.getLong("reserved")),
                                                              Money.of(cost, currency),
                                                              held > 0 ? Money.of(cost.divide(BigDecimal.valueOf(held), 6, RoundingMode.HALF_EVEN), currency) : null);
                                  },
                                  accountId.toString());
    }
}
