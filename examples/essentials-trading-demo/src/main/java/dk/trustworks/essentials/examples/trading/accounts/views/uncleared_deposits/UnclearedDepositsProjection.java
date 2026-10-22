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

package dk.trustworks.essentials.examples.trading.accounts.views.uncleared_deposits;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Every credited deposit still inside its return window, with the business day it clears on - what
 * {@code automations/clear_deposits} works through when a business day closes. A deposit leaves the view when it clears
 * or is returned. Eventually consistent: a deposit credited a moment before a close is cleared at the next one.
 * <p>
 * Idempotent per deposit, so a redelivered event changes nothing; keyed by deposit id, not by stream, because an
 * uncleared deposit carries into the account's next generation and clears there.
 */
@Service
public class UnclearedDepositsProjection extends ViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public UnclearedDepositsProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_uncleared_deposit (
                                              deposit_id VARCHAR PRIMARY KEY,
                                              account_id VARCHAR NOT NULL,
                                              clears_on DATE NOT NULL
                                          )
                                          """);
        this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS projection_uncleared_deposit_clears_on ON projection_uncleared_deposit (clears_on)");
    }

    @Override
    public String getProcessorName() {
        return "UnclearedDepositsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AccountsAggregateTypes.ACCOUNTS);
    }

    @MessageHandler
    void handle(DepositCredited e) {
        jdbcTemplate.update("INSERT INTO projection_uncleared_deposit (deposit_id, account_id, clears_on) VALUES (?, ?, ?) ON CONFLICT (deposit_id) DO NOTHING",
                            e.depositId().toString(), e.accountId().toString(), Date.valueOf(e.clearsOn()));
    }

    @MessageHandler
    void handle(DepositCleared e) {
        jdbcTemplate.update("DELETE FROM projection_uncleared_deposit WHERE deposit_id = ?", e.depositId().toString());
    }

    @MessageHandler
    void handle(DepositReturned e) {
        jdbcTemplate.update("DELETE FROM projection_uncleared_deposit WHERE deposit_id = ?", e.depositId().toString());
    }

    /**
     * @return the deposits that clear on or before {@code businessDate}
     */
    public List<UnclearedDeposit> clearingOnOrBefore(LocalDate businessDate) {
        requireNonNull(businessDate, "No businessDate provided");
        return jdbcTemplate.query("SELECT account_id, deposit_id, clears_on FROM projection_uncleared_deposit WHERE clears_on <= ? ORDER BY clears_on, deposit_id",
                                  (rs, row) -> new UnclearedDeposit(AccountId.of(rs.getString(1)), DepositId.of(rs.getString(2)), rs.getDate(3).toLocalDate()),
                                  Date.valueOf(businessDate));
    }

    public record UnclearedDeposit(AccountId accountId, DepositId depositId, LocalDate clearsOn) {
    }
}
