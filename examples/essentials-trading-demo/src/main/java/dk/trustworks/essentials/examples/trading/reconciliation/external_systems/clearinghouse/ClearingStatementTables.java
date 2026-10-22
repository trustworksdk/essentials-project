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

package dk.trustworks.essentials.examples.trading.reconciliation.external_systems.clearinghouse;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The tables {@link ClearingStatements} stores into: {@code clearing_statement}, {@code clearing_trade} and {@code clearing_obligation}. Created whether or not the simulator is configured, because the
 * breaks, missing-statement and case-opening reads use them regardless - empty without the simulator.
 */
@Component
public class ClearingStatementTables {
    public ClearingStatementTables(JdbcTemplate jdbc) {
        requireNonNull(jdbc, "No jdbc provided");
        jdbc.execute("""
                             CREATE TABLE IF NOT EXISTS clearing_statement (
                                 statement_id VARCHAR PRIMARY KEY,
                                 business_date DATE NOT NULL,
                                 participant_id VARCHAR NOT NULL,
                                 issued_at TIMESTAMPTZ NOT NULL,
                                 received_at TIMESTAMPTZ NOT NULL
                             )
                             """);
        jdbc.execute("""
                             CREATE TABLE IF NOT EXISTS clearing_trade (
                                 statement_id VARCHAR NOT NULL,
                                 execution_id VARCHAR NOT NULL,
                                 instrument_id VARCHAR NOT NULL,
                                 side VARCHAR NOT NULL,
                                 quantity BIGINT NOT NULL,
                                 amount NUMERIC(24, 6) NOT NULL,
                                 trade_date DATE NOT NULL,
                                 obligation_id VARCHAR NOT NULL,
                                 PRIMARY KEY (statement_id, execution_id)
                             )
                             """);
        jdbc.execute("""
                             CREATE TABLE IF NOT EXISTS clearing_obligation (
                                 obligation_id VARCHAR PRIMARY KEY,
                                 statement_id VARCHAR NOT NULL,
                                 instrument_id VARCHAR NOT NULL,
                                 securities_net BIGINT NOT NULL,
                                 cash_net NUMERIC(24, 6) NOT NULL,
                                 trade_count INT NOT NULL
                             )
                             """);
    }
}
