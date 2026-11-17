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
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Design phase 8 §9.1: the engine API. Every definition field reaches {@code el_progress}; {@code StartFrom.head()} is
 * resolved when the row is created and ignored for an existing row; a changed filter digest warns and continues; a page
 * source on another database is refused; the tenant filter, per-subscription error settings and the status snapshot
 */
class EventLogSubscriptionApiIT extends AbstractEventLogIT {
    static final String TYPE = "ApiOrders";

    private EventLog eventLog;
    private final List<SubscriptionHandle> handles = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws SQLException {
        eventLog = startedEventLog(TYPE);
    }

    @AfterEach
    void stop() {
        handles.forEach(SubscriptionHandle::stop);
        eventLog.stop();
    }

    private SubscriptionHandle track(SubscriptionHandle handle) {
        handles.add(handle);
        return handle;
    }

    private void append(String aggregateId, String tenant, int count) throws SQLException {
        appendCommitted(eventLog, new AppendRequest(TYPE, aggregateId, null, tenant, events("OrderPlaced", count)));
    }

    private Map<String, Object> row(String subscriber, String instanceName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT exclusive, consistency_mode, progress_model, resume_from, filter_digest FROM el_progress " +
                                                                 "WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?")) {
            statement.setString(1, subscriber);
            statement.setString(2, TYPE);
            statement.setString(3, instanceName);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                var row = new HashMap<String, Object>();
                row.put("exclusive", resultSet.getBoolean(1));
                row.put("mode", resultSet.getString(2));
                row.put("model", resultSet.getString(3));
                row.put("resume", resultSet.getLong(4));
                row.put("digest", resultSet.getString(5));
                return row;
            }
        }
    }

    private static void awaitTrue(Callable<Boolean> condition) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(20)).until(condition);
    }

    @Test
    void every_definition_field_reaches_the_progress_row() throws Exception {
        var filtered = SubscriptionDefinition.builder("view", TYPE).perInstance("node-1").consistency(ConsistencyMode.CONTIGUOUS)
                                             .eventTypes(EventTypeFilter.of("OrderPlaced")).build();
        track(eventLog.subscribe(filtered, (page, transaction) -> { }));
        var plain = SubscriptionDefinition.builder("reactor", TYPE).build();
        track(eventLog.subscribe(plain, event -> { }));

        var view = row("view", "node-1");
        assertThat(view).containsEntry("exclusive", false).containsEntry("mode", "CONTIGUOUS").containsEntry("model", "IN_HANDLER_TRANSACTION")
                        .containsEntry("digest", filtered.filterDigest());
        assertThat(filtered.filterDigest()).hasSize(64);
        awaitTrue(() -> row("reactor", "") != null);
        assertThat(row("reactor", "")).containsEntry("exclusive", true).containsEntry("mode", "FRESH").containsEntry("model", "PERIODIC").containsEntry("digest", null);
    }

    @Test
    void head_is_resolved_when_the_row_is_created_and_ignored_for_an_existing_row() throws Exception {
        append("a", null, 5);
        var received   = new ConcurrentLinkedQueue<Long>();
        var definition = SubscriptionDefinition.builder("new-only", TYPE).perInstance("node-1").startFrom(StartFrom.head()).build();
        var handle     = eventLog.subscribe(definition, event -> received.add(event.globalOrder()));
        assertThat(row("new-only", "node-1")).containsEntry("resume", 6L);
        append("a", null, 3);
        awaitTrue(() -> received.size() == 3);
        handle.stop();
        assertThat(received).containsExactly(6L, 7L, 8L);

        append("a", null, 2);
        track(eventLog.subscribe(definition, event -> received.add(event.globalOrder())));
        awaitTrue(() -> received.size() == 5);
        assertThat(received).containsExactly(6L, 7L, 8L, 9L, 10L);
    }

    @Test
    void head_of_an_exclusive_subscription_is_resolved_by_its_first_claim() throws Exception {
        append("a", null, 4);
        var received = new ConcurrentLinkedQueue<Long>();
        track(eventLog.subscribe(SubscriptionDefinition.builder("exclusive-new", TYPE).startFrom(StartFrom.head()).build(), event -> received.add(event.globalOrder())));
        awaitTrue(() -> row("exclusive-new", "") != null);
        assertThat(row("exclusive-new", "")).containsEntry("resume", 5L);
        append("a", null, 1);
        awaitTrue(() -> received.size() == 1);
        assertThat(received).containsExactly(5L);
    }

    @Test
    void a_changed_filter_is_stored_and_delivery_continues() throws Exception {
        var first = SubscriptionDefinition.builder("filtered", TYPE).perInstance("node-1").eventTypes(EventTypeFilter.of("OrderPlaced")).build();
        eventLog.subscribe(first, event -> { }).stop();
        assertThat(row("filtered", "node-1")).containsEntry("digest", first.filterDigest());

        var second   = SubscriptionDefinition.builder("filtered", TYPE).perInstance("node-1").tenant("t1").build();
        var received = new ConcurrentLinkedQueue<Long>();
        track(eventLog.subscribe(second, event -> received.add(event.globalOrder())));
        assertThat(second.filterDigest()).isNotEqualTo(first.filterDigest());
        assertThat(row("filtered", "node-1")).containsEntry("digest", second.filterDigest());
        append("a", "t1", 2);
        awaitTrue(() -> received.size() == 2);
    }

    @Test
    void the_tenant_filter_delivers_only_its_tenant_and_counts_the_rest_as_handled() throws Exception {
        append("a", "t1", 2);
        append("b", "t2", 3);
        append("c", "t1", 1);
        var received = new ConcurrentLinkedQueue<StoredEvent>();
        var handle   = track(eventLog.subscribe(SubscriptionDefinition.builder("tenant", TYPE).perInstance("node-1").tenant("t1").build(), received::add));
        var pages    = new ConcurrentLinkedQueue<StoredEvent>();
        var paged    = track(eventLog.subscribe(SubscriptionDefinition.builder("tenant-pages", TYPE).perInstance("node-1").tenant("t2").build(),
                                                (page, transaction) -> pages.addAll(page)));
        awaitTrue(() -> received.size() == 3 && pages.size() == 3);
        assertThat(received).extracting(StoredEvent::tenant).containsOnly("t1");
        assertThat(pages).extracting(StoredEvent::tenant).containsOnly("t2");
        awaitTrue(() -> paged.status().resumePoint() == 7);
        handle.stop();
        assertThat(row("tenant", "node-1")).containsEntry("resume", 7L);
    }

    @Test
    void error_settings_are_per_subscription() throws Exception {
        append("poison", null, 1);
        var noRetry = new ErrorSettings(0, Duration.ofMillis(10), Duration.ofMillis(10), 10);
        track(eventLog.subscribe(SubscriptionDefinition.builder("no-retry", TYPE).perInstance("node-1").errors(noRetry).build(), event -> {
            throw new IllegalStateException("always");
        }));
        var twoRetries = new ErrorSettings(2, Duration.ofMillis(10), Duration.ofMillis(10), 10);
        track(eventLog.subscribe(SubscriptionDefinition.builder("two-retries", TYPE).perInstance("node-1").errors(twoRetries).build(), (page, transaction) -> {
            throw new IllegalStateException("always");
        }));
        awaitTrue(() -> queryLong("SELECT count(*) FROM el_parked_events") == 2);
        assertThat(queryLong("SELECT attempts FROM el_parked_events WHERE subscriber = 'no-retry'")).isEqualTo(1);
        assertThat(queryLong("SELECT attempts FROM el_parked_events WHERE subscriber = 'two-retries'")).isEqualTo(3);
    }

    @Test
    void the_status_is_a_local_snapshot() throws Exception {
        append("a", null, 3);
        var received = new ConcurrentLinkedQueue<Long>();
        var handle   = track(eventLog.subscribe(SubscriptionDefinition.builder("status", TYPE).build(), event -> received.add(event.globalOrder())));
        awaitTrue(() -> received.size() == 3);
        awaitTrue(() -> handle.status().phase().equals("ATTACHED"));
        var status = handle.status();
        assertThat(status.definition()).isEqualTo(handle.definition());
        assertThat(status.progressModel()).isEqualTo("PERIODIC");
        assertThat(status.leaseState()).contains("OWNED");
        assertThat(status.fence()).isPresent();
        assertThat(status.receivedWatermark()).isGreaterThanOrEqualTo(3);
        assertThat(status.lastError()).isEmpty();
        handle.stop();
        assertThat(handle.isRunning()).isFalse();
        assertThat(handle.status().resumePoint()).isEqualTo(4);
        assertThat(handle.status().phase()).isEqualTo("STOPPED");
    }

    @Test
    void a_page_source_binds_its_resources_and_ends_each_page() throws Exception {
        var begun     = new java.util.concurrent.atomic.AtomicInteger();
        var committed = new java.util.concurrent.atomic.AtomicInteger();
        PageTransactionSource source = () -> {
            begun.incrementAndGet();
            var connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            return new PageTransactionSource.OpenPage() {
                public Connection connection() {
                    return connection;
                }

                public <T> T resource(Class<T> type) {
                    if (type == String.class) {
                        return type.cast("bound");
                    }
                    throw new IllegalArgumentException(type.getName());
                }

                public void commit() throws SQLException {
                    try {
                        connection.commit();
                        committed.incrementAndGet();
                    } finally {
                        connection.close();
                    }
                }

                public void rollback() {
                    try {
                        connection.rollback();
                        connection.close();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        };
        append("a", null, 3);
        var resources = new ConcurrentLinkedQueue<String>();
        var handle = track(eventLog.subscribe(SubscriptionDefinition.builder("sourced", TYPE).perInstance("node-1").consistency(ConsistencyMode.CONTIGUOUS).build(),
                                              (page, transaction) -> {
                                                  resources.add(transaction.resource(String.class));
                                                  assertThatThrownBy(() -> transaction.connection().commit()).isInstanceOf(IllegalStateException.class);
                                              }, source));
        awaitTrue(() -> handle.status().resumePoint() == 4);
        assertThat(resources).isNotEmpty().containsOnly("bound");
        assertThat(committed.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void a_page_source_on_another_database_is_refused() throws Exception {
        try {
            execute("CREATE DATABASE el_api_other");
        } catch (SQLException e) {
            if (!"42P04".equals(e.getSQLState())) {
                throw e;
            }
        }
        var other = new PGSimpleDataSource();
        other.setUrl(dataSource.getUrl().replaceFirst("/[^/?]+(\\?|$)", "/el_api_other$1"));
        other.setUser(dataSource.getUser());
        other.setPassword(dataSource.getPassword());
        PageTransactionSource source = () -> {
            var connection = other.getConnection();
            connection.setAutoCommit(false);
            return new PageTransactionSource.OpenPage() {
                public Connection connection() {
                    return connection;
                }

                public <T> T resource(Class<T> type) {
                    throw new IllegalArgumentException(type.getName());
                }

                public void commit() throws SQLException {
                    connection.commit();
                    connection.close();
                }

                public void rollback() {
                    try {
                        connection.rollback();
                        connection.close();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        };
        var definition = SubscriptionDefinition.builder("elsewhere", TYPE).perInstance("node-1").build();
        assertThatThrownBy(() -> eventLog.subscribe(definition, (page, transaction) -> { }, source)).isInstanceOf(IllegalArgumentException.class)
                                                                                                 .hasMessageContaining("el_api_other");
        assertThat(row("elsewhere", "node-1")).isNull();
    }
}
