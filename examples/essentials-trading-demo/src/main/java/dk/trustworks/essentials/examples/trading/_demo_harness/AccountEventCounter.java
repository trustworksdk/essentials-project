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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

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

    /**
     * @param eventSimpleName the event's simple class name; the table stores the fully qualified one
     */
    public long countEventsOfTypeForAccountsStartingWith(String accountIdPrefix, String eventSimpleName) {
        var configuration = eventStore.getAggregateEventStreamConfiguration(AccountsAggregateTypes.ACCOUNTS);
        var columns       = configuration.eventStreamTableColumnNames;
        var countSql = "SELECT count(*) FROM " + configuration.eventStreamTableName
                       + " WHERE " + columns.aggregateIdColumn + " LIKE ? AND " + columns.eventTypeColumn + " LIKE ?";
        var count = jdbcTemplate.queryForObject(countSql, Long.class, accountIdPrefix + "%", "%." + eventSimpleName);
        return count == null ? 0 : count;
    }

    /**
     * Execution id to the time its {@code TradeBooked} was appended, for the order-flow scenario's end-to-end latency.
     */
    public Map<String, Instant> tradeBookingTimesForAccountsStartingWith(String accountIdPrefix) {
        var configuration = eventStore.getAggregateEventStreamConfiguration(AccountsAggregateTypes.ACCOUNTS);
        var columns       = configuration.eventStreamTableColumnNames;
        var sql = "SELECT " + columns.eventPayloadColumn + "->'trade'->>'executionId' AS execution_id, " + columns.timestampColumn + " AS booked_at"
                  + " FROM " + configuration.eventStreamTableName
                  + " WHERE " + columns.aggregateIdColumn + " LIKE ? AND " + columns.eventTypeColumn + " LIKE ?";
        var bookedAt = new HashMap<String, Instant>();
        jdbcTemplate.query(sql, rs -> {
            bookedAt.put(rs.getString("execution_id"), rs.getTimestamp("booked_at").toInstant());
        }, accountIdPrefix + "%", "%.TradeBooked");
        return bookedAt;
    }

    /**
     * Orders of the accounts the exchange accepted that are neither filled, nor asked to cancel, nor closed - for the
     * order-flow scenario, resting at the exchange although they were priced to fill. Order ids start with their
     * account id, so the account prefix selects them.
     */
    public List<OrderId> openAcceptedOrdersForAccountsStartingWith(String accountIdPrefix) {
        var configuration = eventStore.getAggregateEventStreamConfiguration(OrdersAggregateTypes.ORDERS);
        var columns       = configuration.eventStreamTableColumnNames;
        var type          = columns.eventTypeColumn;
        var sql = "SELECT " + columns.aggregateIdColumn + " AS order_id FROM " + configuration.eventStreamTableName
                  + " WHERE " + columns.aggregateIdColumn + " LIKE ?"
                  + " GROUP BY " + columns.aggregateIdColumn
                  + " HAVING bool_or(" + type + " LIKE '%.OrderAccepted')"
                  + " AND NOT bool_or(" + type + " LIKE '%.OrderFilled' OR " + type + " LIKE '%.OrderCancelRequested' OR " + type + " LIKE '%.OrderClosed')";
        return jdbcTemplate.query(sql, (rs, row) -> OrderId.of(rs.getString("order_id")), accountIdPrefix + "%");
    }

    /**
     * The count of one event type on the streams of {@code aggregateType} whose id starts with {@code idPrefix} - for
     * the funding scenario, the outcomes on its deposits and withdrawals.
     */
    public long countEventsOfType(AggregateType aggregateType, String idPrefix, String eventSimpleName) {
        var configuration = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var columns       = configuration.eventStreamTableColumnNames;
        var count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + configuration.eventStreamTableName
                                                + " WHERE " + columns.aggregateIdColumn + " LIKE ? AND " + columns.eventTypeColumn + " LIKE ?",
                                                Long.class, idPrefix + "%", "%." + eventSimpleName);
        return count == null ? 0 : count;
    }

    /**
     * A payload field of one event type on the accounts' streams to the time the event was appended - for the funding
     * scenario's end-to-end latency, the {@code depositId} of {@code DepositCredited} and the {@code withdrawalId} of
     * {@code CashWithdrawn}. Both names are the caller's constants, not a request's.
     */
    public Map<String, Instant> accountEventTimesByPayloadField(String accountIdPrefix, String eventSimpleName, String payloadField) {
        var configuration = eventStore.getAggregateEventStreamConfiguration(AccountsAggregateTypes.ACCOUNTS);
        var columns       = configuration.eventStreamTableColumnNames;
        var sql = "SELECT " + columns.eventPayloadColumn + "->>'" + payloadField + "' AS reference, " + columns.timestampColumn + " AS appended_at"
                  + " FROM " + configuration.eventStreamTableName
                  + " WHERE " + columns.aggregateIdColumn + " LIKE ? AND " + columns.eventTypeColumn + " LIKE ?";
        var appendedAt = new HashMap<String, Instant>();
        jdbcTemplate.query(sql, rs -> {
            appendedAt.put(rs.getString("reference"), rs.getTimestamp("appended_at").toInstant());
        }, accountIdPrefix + "%", "%." + eventSimpleName);
        return appendedAt;
    }
}
