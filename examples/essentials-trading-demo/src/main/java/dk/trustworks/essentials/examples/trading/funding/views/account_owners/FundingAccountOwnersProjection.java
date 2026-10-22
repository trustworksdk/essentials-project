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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.accounts.events.AccountOpened;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code funding}'s own record of who owns which account, projected from the ledger's {@link AccountOpened} - a cross-BC
 * {@code events/} import, as {@code orders} has its own. Every generation repeats the owner, so the upsert is idempotent.
 */
@Service
public class FundingAccountOwnersProjection extends ViewEventProcessor {
    private final JdbcTemplate jdbcTemplate;

    public FundingAccountOwnersProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_funding_account_owner (
                                              account_id VARCHAR PRIMARY KEY,
                                              owner_id VARCHAR NOT NULL
                                          )
                                          """);
    }

    @Override
    public String getProcessorName() {
        return "FundingAccountOwnersProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AccountsAggregateTypes.ACCOUNTS);
    }

    @MessageHandler
    void handle(AccountOpened event) {
        jdbcTemplate.update("""
                                    INSERT INTO projection_funding_account_owner (account_id, owner_id) VALUES (?, ?)
                                    ON CONFLICT (account_id) DO NOTHING
                                    """,
                            event.accountId().toString(),
                            event.ownerId().toString());
    }
}
