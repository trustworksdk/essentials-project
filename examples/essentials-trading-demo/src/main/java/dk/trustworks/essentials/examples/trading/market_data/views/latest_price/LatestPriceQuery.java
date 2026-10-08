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

package dk.trustworks.essentials.examples.trading.market_data.views.latest_price;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Answers "what is this instrument worth right now?" from the read model {@link LatestPriceProjection} owns.
 * <p>
 * The answer is eventually consistent: a price event appended a moment ago may not be visible yet. Do not use this
 * to decide whether something exists before writing it; ask the {@code InstrumentPrices} repository instead, as the
 * demo bootstrap's idempotency probe does.
 */
@Service
public class LatestPriceQuery {
    private final JdbcTemplate jdbcTemplate;

    public LatestPriceQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    @Transactional(readOnly = true)
    public Optional<LatestPrice> findLatestPrice(InstrumentId instrumentId) {
        requireNonNull(instrumentId, "No instrumentId provided");
        return jdbcTemplate.query("""
                                          SELECT latest_price
                                          FROM projection_latest_price
                                          WHERE instrument_id = ?
                                          """,
                                  (rs, rowNum) -> new LatestPrice(instrumentId, Amount.of(rs.getBigDecimal("latest_price"))),
                                  instrumentId.toString())
                           .stream()
                           .findFirst();
    }
}
