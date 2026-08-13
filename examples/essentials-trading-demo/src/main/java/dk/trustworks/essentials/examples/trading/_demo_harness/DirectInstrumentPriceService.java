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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A deliberately <b>non</b>-event-sourced latest-price store, written with raw JDBC, whose only purpose is to be
 * benchmarked against the {@code market_data} aggregate path.
 * <p>
 * It lives in the harness rather than in {@code market_data} precisely because it is a second write path for a concept
 * that context already owns: keeping it here is what guarantees no domain path can read it by accident. The
 * authoritative latest price is always the {@code InstrumentPrice} aggregate — see {@code CLAUDE.md} in this package.
 */
@Service
public class DirectInstrumentPriceService {
    private final JdbcTemplate jdbcTemplate;

    public DirectInstrumentPriceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        ensureTable();
    }

    public void initializePrice(InstrumentId instrumentId, Amount price) {
        upsert(instrumentId, price);
    }

    public void updatePrice(InstrumentId instrumentId, Amount price) {
        upsert(instrumentId, price);
    }

    public Optional<Amount> findLatestPrice(InstrumentId instrumentId) {
        requireNonNull(instrumentId, "No instrumentId provided");
        return jdbcTemplate.query("""
                                          SELECT latest_price
                                          FROM direct_market_data_prices
                                          WHERE instrument_id = ?
                                          """,
                                  rs -> rs.next() ? Optional.of(Amount.of(rs.getBigDecimal("latest_price"))) : Optional.empty(),
                                  instrumentId.toString());
    }

    private void upsert(InstrumentId instrumentId, Amount price) {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(price, "No price provided");
        jdbcTemplate.update("""
                                    INSERT INTO direct_market_data_prices (instrument_id, latest_price, updated_at)
                                    VALUES (?, ?, now())
                                    ON CONFLICT (instrument_id)
                                    DO UPDATE SET latest_price = excluded.latest_price,
                                                  updated_at = excluded.updated_at
                                    """,
                            instrumentId.toString(),
                            price.value());
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
                                     CREATE TABLE IF NOT EXISTS direct_market_data_prices (
                                         instrument_id text PRIMARY KEY,
                                         latest_price numeric(19, 4) NOT NULL,
                                         updated_at timestamptz NOT NULL
                                     )
                                     """);
    }
}
