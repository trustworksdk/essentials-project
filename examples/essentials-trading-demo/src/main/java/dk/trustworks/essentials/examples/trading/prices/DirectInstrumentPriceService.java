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

package dk.trustworks.essentials.examples.trading.prices;

import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Lightweight direct-write latest-price store used to compare against the aggregate-based price path.
 */
@Service
public class DirectInstrumentPriceService {
    private final JdbcTemplate jdbcTemplate;

    public DirectInstrumentPriceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureTable();
    }

    public void initializePrice(InstrumentId instrumentId, BigDecimal price) {
        upsert(instrumentId, price);
    }

    public void updatePrice(InstrumentId instrumentId, BigDecimal price) {
        upsert(instrumentId, price);
    }

    public Optional<BigDecimal> tryLoad(InstrumentId instrumentId) {
        return jdbcTemplate.query("""
                                          SELECT latest_price
                                          FROM direct_market_data_prices
                                          WHERE instrument_id = ?
                                          """,
                                  rs -> rs.next() ? Optional.of(rs.getBigDecimal("latest_price")) : Optional.empty(),
                                  instrumentId.toString());
    }

    private void upsert(InstrumentId instrumentId, BigDecimal price) {
        jdbcTemplate.update("""
                                    INSERT INTO direct_market_data_prices (instrument_id, latest_price, updated_at)
                                    VALUES (?, ?, now())
                                    ON CONFLICT (instrument_id)
                                    DO UPDATE SET latest_price = excluded.latest_price,
                                                  updated_at = excluded.updated_at
                                    """,
                            instrumentId.toString(),
                            price);
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
