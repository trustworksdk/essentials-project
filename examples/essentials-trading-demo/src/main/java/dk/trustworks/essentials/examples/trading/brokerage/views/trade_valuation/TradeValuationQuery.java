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

package dk.trustworks.essentials.examples.trading.brokerage.views.trade_valuation;

import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The one query of the {@code brokerage.trade_valuation} slice, over the one read model it owns.
 * <p>
 * The two derived figures are computed here, from the row, rather than stored in it. Storing them would mean a price
 * tick had to rewrite them for every trade on the instrument — the projection would carry the arithmetic on the write
 * path of the highest-frequency event in the demo, to save a multiplication on a read that happens far less often.
 */
@Service
public class TradeValuationQuery {
    private static final RowMapper<TradeValuation> ROW_MAPPER = (rs, rowNum) -> {
        var side              = TradeSide.valueOf(rs.getString("side"));
        var quantity          = rs.getBigDecimal("quantity");
        var executionPrice    = rs.getBigDecimal("price");
        var settlementId      = rs.getString("settlement_id");
        var latestMarketPrice = rs.getBigDecimal("latest_market_price");

        return new TradeValuation(TradeId.of(rs.getString("trade_id")),
                                  TradingAccountId.of(rs.getString("account_id")),
                                  InstrumentId.of(rs.getString("instrument_id")),
                                  side,
                                  Quantity.of(quantity),
                                  Amount.of(executionPrice),
                                  Amount.of(rs.getBigDecimal("gross_amount")),
                                  rs.getBoolean("executed"),
                                  rs.getBoolean("settlement_requested"),
                                  rs.getBoolean("settled"),
                                  settlementId == null ? null : SettlementId.of(settlementId),
                                  latestMarketPrice == null ? null : Amount.of(latestMarketPrice),
                                  latestMarketPrice == null ? null : Amount.of(latestMarketPrice.multiply(quantity)),
                                  latestMarketPrice == null ? null : Amount.of(unrealizedPnl(side, executionPrice, quantity, latestMarketPrice)));
    };

    private final JdbcTemplate jdbcTemplate;

    public TradeValuationQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    @Transactional(readOnly = true)
    public Optional<TradeValuation> findTradeValuation(TradeId tradeId) {
        requireNonNull(tradeId, "No tradeId provided");
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
                                                 latest_market_price
                                          FROM projection_trade_valuation
                                          WHERE trade_id = ?
                                          """,
                                  ROW_MAPPER,
                                  tradeId.toString())
                           .stream()
                           .findFirst();
    }

    /**
     * The price move since the trade was booked, signed for the side, times the quantity.
     *
     * <p>Ported unchanged from the pre-slice {@code TradeAdminQueryService}, including the sign convention: a
     * {@code SELL} profits when the price falls, so the delta is negated for it. The pre-slice version compared the
     * side with {@code "SELL".equalsIgnoreCase(...)} against a raw {@code String}; the column now round-trips through
     * {@link TradeSide}, so the comparison is on the enum and a typo cannot silently value every trade as a buy.
     */
    private static BigDecimal unrealizedPnl(TradeSide side,
                                            BigDecimal executionPrice,
                                            BigDecimal quantity,
                                            BigDecimal latestMarketPrice) {
        var priceDelta = latestMarketPrice.subtract(executionPrice);
        if (side == TradeSide.SELL) {
            priceDelta = priceDelta.negate();
        }
        return priceDelta.multiply(quantity);
    }
}
