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

package dk.trustworks.essentials.examples.trading.market_data.views.closing_prices;

import dk.trustworks.essentials.examples.trading.market_data.types.*;
import dk.trustworks.essentials.types.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@link ClosingPrices} seam over table {@code closing_price}, which {@link ClosingPriceIntake} fills under the
 * {@code simulators} profile. Without it the table stays empty and every answer is empty.
 */
@Service
public class StoredClosingPrices implements ClosingPrices {
    private final JdbcTemplate jdbcTemplate;

    public StoredClosingPrices(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        jdbcTemplate.execute("""
                                     CREATE TABLE IF NOT EXISTS closing_price (
                                         instrument_id VARCHAR NOT NULL,
                                         business_date DATE NOT NULL,
                                         price NUMERIC(24, 6) NOT NULL,
                                         currency VARCHAR NOT NULL,
                                         basis VARCHAR NOT NULL,
                                         volume BIGINT NOT NULL,
                                         PRIMARY KEY (instrument_id, business_date)
                                     )
                                     """);
    }

    @Override
    public Optional<ClosingPrice> latestOnOrBefore(InstrumentId instrumentId, LocalDate businessDate) {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(businessDate, "No businessDate provided");
        return jdbcTemplate.query("""
                                          SELECT business_date, price, currency, basis FROM closing_price
                                          WHERE instrument_id = ? AND business_date <= ? ORDER BY business_date DESC LIMIT 1
                                          """,
                                  (rs, row) -> new ClosingPrice(instrumentId, rs.getDate(1).toLocalDate(), Money.of(rs.getBigDecimal(2), rs.getString(3)), rs.getString(4)),
                                  instrumentId.toString(), Date.valueOf(businessDate))
                           .stream().findFirst();
    }

    @Override
    public Optional<LocalDate> latestBusinessDate() {
        return Optional.ofNullable(jdbcTemplate.queryForObject("SELECT max(business_date) FROM closing_price", LocalDate.class));
    }
}
