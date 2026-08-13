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

package dk.trustworks.essentials.examples.trading.market_data.views.instrument_details;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The two queries this slice serves over {@code projection_instrument_details} — list, and lookup by id.
 * Both interrogate the same read model, so they are one slice (§R2).
 */
@Service
public class InstrumentDetailsQuery {
    private final JdbcTemplate jdbcTemplate;

    public InstrumentDetailsQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    public List<InstrumentDetails> instruments() {
        return jdbcTemplate.query("""
                                          SELECT instrument_id, symbol, display_name, suspended, suspension_reason
                                          FROM projection_instrument_details
                                          ORDER BY instrument_id
                                          """,
                                  (rs, rowNum) -> toInstrumentDetails(rs.getString("instrument_id"),
                                                                      rs.getString("symbol"),
                                                                      rs.getString("display_name"),
                                                                      rs.getBoolean("suspended"),
                                                                      rs.getString("suspension_reason")));
    }

    public Optional<InstrumentDetails> findInstrumentDetails(InstrumentId instrumentId) {
        requireNonNull(instrumentId, "No instrumentId provided");
        return jdbcTemplate.query("""
                                          SELECT instrument_id, symbol, display_name, suspended, suspension_reason
                                          FROM projection_instrument_details
                                          WHERE instrument_id = ?
                                          """,
                                  rs -> rs.next()
                                          ? Optional.of(toInstrumentDetails(rs.getString("instrument_id"),
                                                                            rs.getString("symbol"),
                                                                            rs.getString("display_name"),
                                                                            rs.getBoolean("suspended"),
                                                                            rs.getString("suspension_reason")))
                                          : Optional.empty(),
                                  instrumentId.toString());
    }

    private static InstrumentDetails toInstrumentDetails(String instrumentId,
                                                         String symbol,
                                                         String displayName,
                                                         boolean suspended,
                                                         String suspensionReason) {
        return new InstrumentDetails(InstrumentId.of(instrumentId),
                                     Symbol.of(symbol),
                                     displayName,
                                     suspended,
                                     suspensionReason);
    }
}
