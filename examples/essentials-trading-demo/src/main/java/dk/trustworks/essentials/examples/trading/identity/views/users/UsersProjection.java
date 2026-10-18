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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projects user events into {@code projection_users}. Every statement assigns rather than accumulates, so a redelivery
 * restates a row instead of compounding it. Eventually consistent: logins never read it ({@code user_credentials} is
 * the strongly consistent half).
 */
@Service
public class UsersProjection extends ViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public UsersProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_users (
                                              user_id VARCHAR PRIMARY KEY,
                                              email VARCHAR NOT NULL,
                                              display_name VARCHAR NOT NULL,
                                              roles VARCHAR NOT NULL,
                                              locked BOOLEAN NOT NULL DEFAULT FALSE,
                                              lock_reason VARCHAR NULL,
                                              updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          )
                                          """);
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
        jdbcTemplate.update("""
                                    INSERT INTO projection_users (user_id, email, display_name, roles, locked, updated_at)
                                    VALUES (?, ?, ?, ?, false, CURRENT_TIMESTAMP)
                                    ON CONFLICT (user_id) DO UPDATE
                                    SET email = EXCLUDED.email,
                                        display_name = EXCLUDED.display_name,
                                        roles = EXCLUDED.roles,
                                        updated_at = CURRENT_TIMESTAMP
                                    """,
                            event.userId().toString(),
                            event.email().toString(),
                            event.displayName(),
                            event.roles().stream().map(Role::name).sorted().collect(Collectors.joining(",")));
    }

    @MessageHandler
    void handle(UserLocked event) {
        jdbcTemplate.update("UPDATE projection_users SET locked = true, lock_reason = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                            event.reason(),
                            event.userId().toString());
    }

    @MessageHandler
    void handle(UserUnlocked event) {
        jdbcTemplate.update("UPDATE projection_users SET locked = false, lock_reason = NULL, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                            event.userId().toString());
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_users");
    }
}
