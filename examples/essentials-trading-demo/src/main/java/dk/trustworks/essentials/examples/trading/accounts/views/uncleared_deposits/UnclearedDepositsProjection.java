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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import org.jdbi.v3.core.Handle;
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
 * <p>
 * Engine-neutral: the table, the event logic and the query, on either event store engine. What feeds it is the engine's
 * processor - {@link UnclearedDepositsClassicView} or {@link UnclearedDepositsEventLogView} - which hands each event
 * here with the handle of the transaction it handles the event in, so the row commits together with that processor's
 * progress. The constructor's DDL and {@link #clearingOnOrBefore(LocalDate)} open (or join) a unit of work of their own.
 */
@Service
public class UnclearedDepositsProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public UnclearedDepositsProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                              CREATE TABLE IF NOT EXISTS projection_uncleared_deposit (
                                                                                  deposit_id VARCHAR PRIMARY KEY,
                                                                                  account_id VARCHAR NOT NULL,
                                                                                  clears_on DATE NOT NULL
                                                                              )
                                                                              """));
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), "CREATE INDEX IF NOT EXISTS projection_uncleared_deposit_clears_on ON projection_uncleared_deposit (clears_on)"));
    }

    void on(Handle handle, DepositCredited e) {
        handle.createUpdate("INSERT INTO projection_uncleared_deposit (deposit_id, account_id, clears_on) VALUES (:depositId, :accountId, :clearsOn) ON CONFLICT (deposit_id) DO NOTHING")
              .bind("depositId", e.depositId().toString())
              .bind("accountId", e.accountId().toString())
              .bind("clearsOn", Date.valueOf(e.clearsOn()))
              .execute();
    }

    void on(Handle handle, DepositCleared e) {
        removeDeposit(handle, e.depositId());
    }

    void on(Handle handle, DepositReturned e) {
        removeDeposit(handle, e.depositId());
    }

    private static void removeDeposit(Handle handle, DepositId depositId) {
        handle.createUpdate("DELETE FROM projection_uncleared_deposit WHERE deposit_id = :depositId")
              .bind("depositId", depositId.toString())
              .execute();
    }

    /**
     * @return the deposits that clear on or before {@code businessDate}
     */
    public List<UnclearedDeposit> clearingOnOrBefore(LocalDate businessDate) {
        requireNonNull(businessDate, "No businessDate provided");
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT account_id, deposit_id, clears_on FROM projection_uncleared_deposit WHERE clears_on <= :businessDate ORDER BY clears_on, deposit_id")
                                                          .bind("businessDate", Date.valueOf(businessDate))
                                                          .map((rs, ctx) -> new UnclearedDeposit(AccountId.of(rs.getString(1)), DepositId.of(rs.getString(2)), rs.getDate(3).toLocalDate()))
                                                          .list());
    }

    public record UnclearedDeposit(AccountId accountId, DepositId depositId, LocalDate clearsOn) {
    }
}
