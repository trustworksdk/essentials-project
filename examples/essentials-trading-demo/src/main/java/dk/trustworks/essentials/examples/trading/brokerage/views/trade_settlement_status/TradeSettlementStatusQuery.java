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

package dk.trustworks.essentials.examples.trading.brokerage.views.trade_settlement_status;

import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementStatus;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The two queries of the {@code brokerage.trade_settlement_status} slice, over the one read model it owns.
 * <p>
 * Both interrogate {@code projection_trade_settlement} — the full list, and one row looked up by settlement id, which
 * the partial unique index makes a single-row lookup. Two queries over the same model is one slice (§R2).
 */
@Service
public class TradeSettlementStatusQuery {
    private static final String COLUMNS = """
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
            """;

    /**
     * Every column but {@code trade_id} and {@code settlement_status} is nullable, because the row is a join of two
     * independent streams and either half may still be missing — so each is mapped through {@link #mapNullable}
     * rather than wrapped directly.
     */
    private static final RowMapper<TradeSettlementStatus> ROW_MAPPER =
            (rs, rowNum) -> new TradeSettlementStatus(TradeId.of(rs.getString("trade_id")),
                                                      mapNullable(rs.getString("account_id"), TradingAccountId::of),
                                                      mapNullable(rs.getString("instrument_id"), InstrumentId::of),
                                                      mapNullable(rs.getString("side"), TradeSide::valueOf),
                                                      mapNullable(rs.getBigDecimal("quantity"), Quantity::of),
                                                      mapNullable(rs.getBigDecimal("price"), Amount::of),
                                                      mapNullable(rs.getBigDecimal("gross_amount"), Amount::of),
                                                      rs.getBoolean("executed"),
                                                      rs.getBoolean("settlement_requested"),
                                                      rs.getBoolean("settled"),
                                                      mapNullable(rs.getString("settlement_id"), SettlementId::of),
                                                      SettlementStatus.valueOf(rs.getString("settlement_status")));

    private final JdbcTemplate jdbcTemplate;

    public TradeSettlementStatusQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    @Transactional(readOnly = true)
    public List<TradeSettlementStatus> tradeSettlements() {
        return jdbcTemplate.query(COLUMNS + "ORDER BY trade_id", ROW_MAPPER);
    }

    /**
     * One settlement, read off the projection rather than off a rehydrated {@code Settlement} aggregate.
     * {@link Optional#empty()} covers both "no such settlement" and "not projected yet" — the projection is
     * asynchronous, so the two are genuinely indistinguishable from here.
     */
    @Transactional(readOnly = true)
    public Optional<SettlementStatusView> findSettlement(SettlementId settlementId) {
        requireNonNull(settlementId, "No settlementId provided");
        return jdbcTemplate.query(COLUMNS + "WHERE settlement_id = ?",
                                  ROW_MAPPER,
                                  settlementId.toString())
                           .stream()
                           .findFirst()
                           .map(SettlementStatusView::from);
    }

    /**
     * A {@code NULL} column stays {@code null} in the read shape rather than becoming an empty semantic type — an
     * absent instrument is not {@code InstrumentId("")}.
     */
    private static <COLUMN, VALUE> VALUE mapNullable(COLUMN column, Function<COLUMN, VALUE> map) {
        return column == null ? null : map.apply(column);
    }
}
