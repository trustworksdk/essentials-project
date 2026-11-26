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

package dk.trustworks.essentials.examples.trading.funding.views.account_owners;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.accounts.events.AccountOpened;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code funding}'s own record of who owns which account, projected from the ledger's {@link AccountOpened} - a cross-BC
 * {@code events/} import, as {@code orders} has its own. Every generation repeats the owner, so the upsert is idempotent.
 * <p>
 * Engine-neutral: the tables, the event logic and the queries, on either event store engine. What feeds it is the
 * engine's processor - {@link FundingAccountOwnersClassicView} or {@link FundingAccountOwnersEventLogView} - which hands each event here with
 * the handle of the transaction it handles the event in, so the row commits together with that processor's progress.
 * The constructor's DDL opens a unit of work of its own.
 */
@Service
public class FundingAccountOwnersProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public FundingAccountOwnersProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                              CREATE TABLE IF NOT EXISTS projection_funding_account_owner (
                                                                                  account_id VARCHAR PRIMARY KEY,
                                                                                  owner_id VARCHAR NOT NULL
                                                                              )
                                                                              """));
    }

    void on(Handle handle, AccountOpened event) {
        handle.createUpdate("""
                                INSERT INTO projection_funding_account_owner (account_id, owner_id) VALUES (:accountId, :ownerId)
                                ON CONFLICT (account_id) DO NOTHING
                                """)
              .bind("accountId", event.accountId().toString())
              .bind("ownerId", event.ownerId().toString())
              .execute();
    }
}
