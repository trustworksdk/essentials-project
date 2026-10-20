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
package dk.trustworks.essentials.examples.trading.orders.views.late_fills;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.types.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Service
public class LateFillsQuery {
    private static final RowMapper<LateFill> ROW = (rs, rowNum) ->
            new LateFill(OrderId.of(rs.getString("order_id")),
                         AccountId.of(rs.getString("account_id")),
                         InstrumentId.of(rs.getString("instrument_id")),
                         TradeSide.valueOf(rs.getString("side")),
                         ExecutionId.of(rs.getString("execution_id")),
                         Quantity.of(rs.getLong("quantity")),
                         Money.of(rs.getBigDecimal("price"), CurrencyCode.of(rs.getString("currency"))),
                         OrderStatus.valueOf(rs.getString("closed_as")),
                         rs.getObject("recorded_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public LateFillsQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    /**
     * Newest first.
     */
    public List<LateFill> lateFills() {
        return jdbcTemplate.query("""
                                          SELECT order_id, account_id, instrument_id, side, execution_id, quantity, price, currency, closed_as, recorded_at
                                          FROM projection_late_fills
                                          ORDER BY recorded_at DESC, execution_id
                                          """, ROW);
    }
}
