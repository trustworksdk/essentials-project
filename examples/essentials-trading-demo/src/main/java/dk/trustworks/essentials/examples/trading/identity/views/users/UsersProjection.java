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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.identity.events.*;
import dk.trustworks.essentials.examples.trading.identity.types.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Projects user events into {@code projection_users}. Every statement assigns rather than accumulates, so a redelivery
 * restates a row instead of compounding it. Eventually consistent: logins never read it ({@code user_credentials} is
 * the strongly consistent half).
 * <p>
 * Every handler write goes through {@code getRequiredUnitOfWork().handle()…}, the {@code UnitOfWork} a
 * {@link ViewEventProcessor} handles each event in, so the row and the processor's progress commit together. The
 * constructor's DDL and the reset run where none is active, so they go through
 * {@code getUnitOfWorkFactory().usingUnitOfWork(uow -> …)}, which opens one.
 */
@Service
public class UsersProjection extends TradingDemoViewEventProcessor {
    public UsersProjection(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("""
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

    @Override
    public String getProcessorName() {
        return "UsersProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(IdentityAggregateTypes.USERS);
    }

    @MessageHandler
    void handle(UserRegistered event) {
        getRequiredUnitOfWork().handle().createUpdate("""
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

    @MessageHandler
    void handle(UserLocked event) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_users SET locked = true, lock_reason = :lockReason, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId")
                                .bind("lockReason", event.reason())
                                .bind("userId", event.userId().toString())
                                .execute();
    }

    @MessageHandler
    void handle(UserUnlocked event) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_users SET locked = false, lock_reason = NULL, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId")
                                .bind("userId", event.userId().toString())
                                .execute();
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("TRUNCATE TABLE projection_users"));
    }
}
