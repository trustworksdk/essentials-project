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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Counts the events actually appended for a load run's accounts, straight from the {@code Accounts} event table, so
 * the ledger load scenario can tell when a view has caught up without predicting how many events a run produced (a
 * failed command produces none, a no-op command none either). Infrastructure, not a domain read: it reads the event
 * store's table through the store's own configuration of it.
 */
@Component
public class AccountEventCounter {
    private final JdbcTemplate                                                         jdbcTemplate;
    private final ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    public AccountEventCounter(JdbcTemplate jdbcTemplate, ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
    }

    public long countEventsForAccountsStartingWith(String accountIdPrefix) {
        // Resolved per call, not in the constructor: an aggregate type's stream configuration is registered lazily,
        // when its repository is first built, which can be after this bean is
        var configuration = eventStore.getAggregateEventStreamConfiguration(AccountsAggregateTypes.ACCOUNTS);
        // Both names come from the event store's configuration, not from a request
        var countSql = "SELECT count(*) FROM " + configuration.eventStreamTableName
                       + " WHERE " + configuration.eventStreamTableColumnNames.aggregateIdColumn + " LIKE ?";
        var count = jdbcTemplate.queryForObject(countSql, Long.class, accountIdPrefix + "%");
        return count == null ? 0 : count;
    }
}
