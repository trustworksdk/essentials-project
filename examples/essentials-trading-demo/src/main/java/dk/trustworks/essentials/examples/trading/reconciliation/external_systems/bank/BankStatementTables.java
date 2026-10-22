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

package dk.trustworks.essentials.examples.trading.reconciliation.external_systems.bank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The tables {@link BankStatements} stores into: {@code bank_statement} and {@code bank_statement_entry}. Created whether or not the simulator is configured, because the
 * breaks, missing-statement and case-opening reads use them regardless - empty without the simulator.
 */
@Component
public class BankStatementTables {
    public BankStatementTables(JdbcTemplate jdbc) {
        requireNonNull(jdbc, "No jdbc provided");
        jdbc.execute("""
                             CREATE TABLE IF NOT EXISTS bank_statement (
                                 statement_id VARCHAR PRIMARY KEY,
                                 business_date DATE NOT NULL,
                                 currency VARCHAR NOT NULL,
                                 opening_balance NUMERIC(24, 6) NOT NULL,
                                 closing_balance NUMERIC(24, 6) NOT NULL,
                                 issued_at TIMESTAMPTZ NOT NULL,
                                 received_at TIMESTAMPTZ NOT NULL
                             )
                             """);
        jdbc.execute("""
                             CREATE TABLE IF NOT EXISTS bank_statement_entry (
                                 entry_id VARCHAR PRIMARY KEY,
                                 statement_id VARCHAR NOT NULL,
                                 end_to_end_id VARCHAR NOT NULL,
                                 payment_id VARCHAR NOT NULL,
                                 kind VARCHAR NOT NULL,
                                 amount NUMERIC(24, 6) NOT NULL,
                                 value_date DATE NOT NULL
                             )
                             """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS bank_statement_entry_statement ON bank_statement_entry (statement_id)");
    }
}
