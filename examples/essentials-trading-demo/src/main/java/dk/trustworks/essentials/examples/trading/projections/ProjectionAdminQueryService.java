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

package dk.trustworks.essentials.examples.trading.projections;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Query access to the demo's projection tables.
 */
@Service
public class ProjectionAdminQueryService {
    private final JdbcTemplate jdbcTemplate;

    public ProjectionAdminQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TradingAccountStatementProjectionView> accountStatements() {
        return jdbcTemplate.query("""
                                          SELECT logical_account_id,
                                                 owner_id,
                                                 period_id,
                                                 current_generation,
                                                 generation_count,
                                                 cash_balance,
                                                 reserved_funds,
                                                 realized_pnl,
                                                 books_closed
                                          FROM projection_trading_account_statement
                                          ORDER BY logical_account_id
                                          """,
                                  (rs, rowNum) -> new TradingAccountStatementProjectionView(
                                          rs.getString("logical_account_id"),
                                          rs.getString("owner_id"),
                                          rs.getString("period_id"),
                                          rs.getInt("current_generation"),
                                          rs.getInt("generation_count"),
                                          rs.getBigDecimal("cash_balance"),
                                          rs.getBigDecimal("reserved_funds"),
                                          rs.getBigDecimal("realized_pnl"),
                                          rs.getBoolean("books_closed")));
    }

    public List<TradeSettlementProjectionView> tradeSettlements() {
        return jdbcTemplate.query("""
                                          SELECT trade_id,
                                                 account_id,
                                                 instrument_id,
                                                 side,
                                                 quantity,
                                                 price,
                                                 gross_amount,
                                                 executed,
                                                 settlement_requested,
                                                 settled,
                                                 settlement_id,
                                                 settlement_status
                                          FROM projection_trade_settlement
                                          ORDER BY trade_id
                                          """,
                                  (rs, rowNum) -> new TradeSettlementProjectionView(
                                          rs.getString("trade_id"),
                                          rs.getString("account_id"),
                                          rs.getString("instrument_id"),
                                          rs.getString("side"),
                                          rs.getBigDecimal("quantity"),
                                          rs.getBigDecimal("price"),
                                          rs.getBigDecimal("gross_amount"),
                                          rs.getBoolean("executed"),
                                          rs.getBoolean("settlement_requested"),
                                          rs.getBoolean("settled"),
                                          rs.getString("settlement_id"),
                                          rs.getString("settlement_status")));
    }
}
