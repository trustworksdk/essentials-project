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

package dk.trustworks.essentials.examples.trading.identity.views.users;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
import dk.trustworks.essentials.examples.trading.identity.events.*;
import dk.trustworks.essentials.examples.trading.identity.types.*;
import org.jdbi.v3.core.Handle;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects user events into {@code projection_users}. Every statement assigns rather than accumulates, so a redelivery
 * restates a row instead of compounding it. Eventually consistent: logins never read it ({@code user_credentials} is
 * the strongly consistent half).
 * <p>
 * Engine-neutral: the tables, the event logic and the queries, on either event store engine. What feeds it is the
 * engine's processor - {@link UsersClassicView} or {@link UsersEventLogView} - which hands each event here with
 * the handle of the transaction it handles the event in, so the row commits together with that processor's progress.
 * The constructor's DDL and {@link #truncate()}, which the classic processor calls when its subscriptions are reset,
 * open a unit of work of their own.
 */
@Service
public class UsersProjection {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    public UsersProjection(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        unitOfWorkFactory.usingUnitOfWork(uow -> TradingDemoSchema.ddl(uow.handle(), """
                                                                              CREATE TABLE IF NOT EXISTS projection_users (
                                                                                  user_id VARCHAR PRIMARY KEY,
                                                                                  email VARCHAR NOT NULL,
                                                                                  display_name VARCHAR NOT NULL,
                                                                                  roles VARCHAR NOT NULL,
                                                                                  locked BOOLEAN NOT NULL DEFAULT FALSE,
                                                                                  lock_reason VARCHAR NULL,
                                                                                  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                                              )
                                                                              """));
    }

    void on(Handle handle, UserRegistered event) {
        handle.createUpdate("""
                                INSERT INTO projection_users (user_id, email, display_name, roles, locked, updated_at)
                                VALUES (:userId, :email, :displayName, :roles, false, CURRENT_TIMESTAMP)
                                ON CONFLICT (user_id) DO UPDATE
                                SET email = EXCLUDED.email,
                                    display_name = EXCLUDED.display_name,
                                    roles = EXCLUDED.roles,
                                    updated_at = CURRENT_TIMESTAMP
                                """)
              .bind("userId", event.userId().toString())
              .bind("email", event.email().toString())
              .bind("displayName", event.displayName())
              .bind("roles", event.roles().stream().map(Role::name).sorted().collect(Collectors.joining(",")))
              .execute();
    }

    void on(Handle handle, UserLocked event) {
        handle.createUpdate("UPDATE projection_users SET locked = true, lock_reason = :lockReason, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId")
              .bind("lockReason", event.reason())
              .bind("userId", event.userId().toString())
              .execute();
    }

    void on(Handle handle, UserUnlocked event) {
        handle.createUpdate("UPDATE projection_users SET locked = false, lock_reason = NULL, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId")
              .bind("userId", event.userId().toString())
              .execute();
    }

    /**
     * Empties the view, for a processor whose subscriptions are reset to replay it from the start.
     */
    void truncate() {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("TRUNCATE TABLE projection_users"));
    }

}
