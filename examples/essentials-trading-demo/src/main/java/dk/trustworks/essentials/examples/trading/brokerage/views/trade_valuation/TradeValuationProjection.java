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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trades;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementRequested;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradeExecuted;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradePlaced;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradeSettled;
import dk.trustworks.essentials.examples.trading.market_data.events.PriceInitialized;
import dk.trustworks.essentials.examples.trading.market_data.events.PriceUpdated;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.MarketDataAggregateTypes;
import dk.trustworks.essentials.types.Amount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The projector of the {@code brokerage.trade_valuation} view slice — events in, read model out. A view slice never
 * produces events (rules/slice-design.md § The four slice kinds).
 * <p>
 * <b>This is the slice that made {@code GET /api/admin/trades/{tradeId}} legal.</b> Valuing a trade needs the market
 * price of its instrument, and the instrument is {@code market_data}'s concept, not this context's. The pre-slice
 * version got that price by injecting {@code market_data}'s <em>write-side</em> price service and loading the
 * {@code InstrumentPrice} aggregate — one bounded context reaching into another's write model, which §R4 forbids
 * outright.
 * <p>
 * The CQRS answer is to project what you need into a model you own. So this processor subscribes to
 * {@link Trades#AGGREGATE_TYPE} <em>and</em> {@link MarketDataAggregateTypes#INSTRUMENT_PRICES}, and importing
 * {@link PriceInitialized} / {@link PriceUpdated} from {@code market_data.events} is exactly the cross-context import
 * the law allows: {@code events/} and {@code types/} are that context's public surface.
 * <p>
 * A price event fans out across every trade on that instrument — one {@code UPDATE … WHERE instrument_id = ?} — which
 * is why the table carries an index on {@code instrument_id}. That fan-out is the cost of owning the price locally,
 * and it is paid on the write path of a projection rather than on every read.
 */
@Service
public class TradeValuationProjection extends EventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public TradeValuationProjection(EventProcessorDependencies dependencies,
                                    JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_trade_valuation (
                                              trade_id VARCHAR PRIMARY KEY,
                                              account_id VARCHAR NOT NULL,
                                              instrument_id VARCHAR NOT NULL,
                                              side VARCHAR NOT NULL,
                                              quantity NUMERIC(19, 4) NOT NULL,
                                              price NUMERIC(19, 4) NOT NULL,
                                              gross_amount NUMERIC(19, 4) NOT NULL,
                                              executed BOOLEAN NOT NULL DEFAULT FALSE,
                                              settlement_requested BOOLEAN NOT NULL DEFAULT FALSE,
                                              settled BOOLEAN NOT NULL DEFAULT FALSE,
                                              settlement_id VARCHAR NULL,
                                              latest_market_price NUMERIC(19, 4) NULL,
                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
        this.jdbcTemplate.execute("""
                                          CREATE INDEX IF NOT EXISTS projection_trade_valuation_instrument_id_idx
                                          ON projection_trade_valuation (instrument_id)
                                          """);
        // The slice's second table, and the reason this projection is order-independent -- see applyMarketPrice.
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_trade_valuation_price (
                                              instrument_id VARCHAR PRIMARY KEY,
                                              latest_price NUMERIC(19, 4) NOT NULL,
                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
    }

    @Override
    public String getProcessorName() {
        return "TradeValuationProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Trades.AGGREGATE_TYPE,
                       MarketDataAggregateTypes.INSTRUMENT_PRICES);
    }

    /**
     * The only handler that creates a row — every column but the price is known here, and a trade that has never been
     * placed has nothing to value.
     * <p>
     * It seeds {@code latest_market_price} from the price this slice has already recorded for the instrument, which is
     * what makes the row correct when the price ticks arrive <em>before</em> the trade. On the conflict branch the
     * price is left alone instead: a redelivered {@code TradePlaced} restates the trade's booked terms, and must not
     * roll a newer price back to whatever was current when the trade was first projected.
     */
    @MessageHandler
    void handle(TradePlaced event) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_trade_valuation (
                                        trade_id,
                                        account_id,
                                        instrument_id,
                                        side,
                                        quantity,
                                        price,
                                        gross_amount,
                                        executed,
                                        settlement_requested,
                                        settled,
                                        latest_market_price,
                                        updated_at
                                    )
                                    SELECT ?, ?, ?, ?, ?, ?, ?, false, false, false,
                                           (SELECT latest_price
                                            FROM projection_trade_valuation_price
                                            WHERE instrument_id = ?),
                                           CURRENT_TIMESTAMP
                                    ON CONFLICT (trade_id) DO UPDATE
                                    SET account_id = EXCLUDED.account_id,
                                        instrument_id = EXCLUDED.instrument_id,
                                        side = EXCLUDED.side,
                                        quantity = EXCLUDED.quantity,
                                        price = EXCLUDED.price,
                                        gross_amount = EXCLUDED.gross_amount,
                                        updated_at = CURRENT_TIMESTAMP
                                    """,
                            event.tradeId().toString(),
                            event.accountId().toString(),
                            event.instrumentId().toString(),
                            event.side().name(),
                            event.quantity().value(),
                            event.price().value(),
                            event.grossAmount().value(),
                            event.instrumentId().toString());
    }

    @MessageHandler
    void handle(TradeExecuted event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_valuation
                                    SET executed = true,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE trade_id = ?
                                    """,
                            event.tradeId().toString());
    }

    @MessageHandler
    void handle(SettlementRequested event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_valuation
                                    SET settlement_requested = true,
                                        settlement_id = ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE trade_id = ?
                                    """,
                            event.settlementId().toString(),
                            event.tradeId().toString());
    }

    @MessageHandler
    void handle(TradeSettled event) {
        jdbcTemplate.update("""
                                    UPDATE projection_trade_valuation
                                    SET settled = true,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE trade_id = ?
                                    """,
                            event.tradeId().toString());
    }

    @MessageHandler
    void handle(PriceInitialized event) {
        applyMarketPrice(event.instrumentId(), event.price());
    }

    @MessageHandler
    void handle(PriceUpdated event) {
        applyMarketPrice(event.instrumentId(), event.price());
    }

    /**
     * Rebuild support: wipe both of this slice's tables so a subscription reset replays cleanly. Called once per
     * {@link AggregateType} this processor subscribes to — and it truncates them fully each time, because a row is fed
     * by both of them and cannot be deleted per aggregate type.
     */
    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType,
                                        GlobalEventOrder resubscribeFromAndIncluding) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_trade_valuation");
        jdbcTemplate.execute("TRUNCATE TABLE projection_trade_valuation_price");
    }

    /**
     * The cross-context half of the join: one price tick restates every trade on that instrument, and is also recorded
     * per instrument so a trade projected <em>later</em> can seed its own market price from it.
     *
     * <p><b>Why the second write is not redundant.</b> This processor subscribes to two aggregate types, {@code Trades}
     * and {@code InstrumentPrices}, and they are two independent subscriptions. {@code GlobalEventOrder} sequences
     * events within one aggregate type, not across two, so there is no ordering guarantee between a price tick and a
     * trade on the same instrument — either can be projected first. With only the {@code UPDATE} below, the price-first
     * interleaving matched no rows and the trade's {@code latest_market_price} stayed {@code NULL}
     * <em>permanently</em>, because nothing replays a tick that has already been consumed. Continuous demo traffic hid
     * it: the next tick a second later fixed the row, so it only surfaced when the price stopped moving — which is
     * exactly what an integration test does.
     *
     * <p>Both price events carry the full price rather than a delta, so both writes are idempotent under redelivery.
     */
    private void applyMarketPrice(InstrumentId instrumentId, Amount price) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_trade_valuation_price (instrument_id, latest_price, updated_at)
                                    VALUES (?, ?, CURRENT_TIMESTAMP)
                                    ON CONFLICT (instrument_id) DO UPDATE
                                    SET latest_price = EXCLUDED.latest_price,
                                        updated_at = CURRENT_TIMESTAMP
                                    """,
                            instrumentId.toString(),
                            price.value());
        jdbcTemplate.update("""
                                    UPDATE projection_trade_valuation
                                    SET latest_market_price = ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE instrument_id = ?
                                    """,
                            price.value(),
                            instrumentId.toString());
    }
}
