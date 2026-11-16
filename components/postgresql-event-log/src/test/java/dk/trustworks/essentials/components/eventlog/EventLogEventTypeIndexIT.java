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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.schema.SchemaOwnership;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 5 §5, §7.3: the opt-in {@code (event_type, global_order)} index is built concurrently on an existing table while
 * appends run, an invalid one (a cancelled build) is dropped and rebuilt, and where the engine does not create the
 * schema nothing is built and the warning names the statement. Validity is read from {@code pg_index}. Data sizes scale
 * up with {@code -Dbenchmark.run=true}.
 */
class EventLogEventTypeIndexIT extends AbstractEventLogIT {
    private static final String TYPE  = "Orders";
    private static final int    ROWS  = Boolean.getBoolean("benchmark.run") ? 200_000 : 20_000;

    @Test
    void the_index_is_built_concurrently_while_appends_run() throws Exception {
        var plain = startedEventLog(TYPE);
        seed(plain, ROWS);
        var indexed = eventLog(EventLogSettings.defaults().withEventTypeIndexed(Set.of(TYPE)), SchemaOwnership.COMPONENT);
        var stop    = new AtomicBoolean();
        var slowest = new AtomicLong();
        var appends = new AtomicInteger();
        var writer = Thread.ofVirtual().start(() -> {
            try {
                while (!stop.get()) {
                    var started = System.nanoTime();
                    appendCommitted(plain, AppendRequest.withoutExpectedOrder(TYPE, "w", List.of(event("W", "{}"))));
                    slowest.accumulateAndGet(System.nanoTime() - started, Math::max);
                    appends.incrementAndGet();
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        var tables = indexed.registerAggregateType(TYPE);
        var valid  = indexed.eventTypeIndex().build(TYPE).get(60, TimeUnit.SECONDS);
        stop.set(true);
        writer.join();
        assertThat(valid).isTrue();
        assertThat(validity(tables)).isTrue();
        assertThat(indexed.eventTypeIndex().valid(TYPE)).isTrue();
        assertThat(appends.get()).as("appends ran during the build").isPositive();
        assertThat(slowest.get()).as("no append waited a second").isLessThan(TimeUnit.SECONDS.toNanos(1));
        // Idempotent: a second start finds it valid and builds nothing
        var again = eventLog(EventLogSettings.defaults().withEventTypeIndexed(Set.of(TYPE)), SchemaOwnership.COMPONENT);
        again.registerAggregateType(TYPE);
        assertThat(again.eventTypeIndex().build(TYPE).get(60, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void an_invalid_index_left_by_a_cancelled_build_is_dropped_and_rebuilt() throws Exception {
        var plain  = startedEventLog(TYPE);
        var tables = plain.registerAggregateType(TYPE);
        seed(plain, 1_000);
        var create = EventLogSchemaContributor.eventTypeIndexStatements(tables).getFirst();
        // An open transaction with an xid makes the concurrent build wait; cancelling it then leaves the index invalid
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            queryLong(blocker, "SELECT count(*) FROM " + tables.eventTable() + " WHERE pg_current_xact_id() IS NOT NULL AND global_order < 0");
            var failure = new AtomicReference<SQLException>();
            var builder = Thread.ofVirtual().start(() -> {
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute(create);
                } catch (SQLException e) {
                    failure.set(e);
                }
            });
            await().atMost(java.time.Duration.ofSeconds(30))
                   .until(() -> queryLong("SELECT count(*) FROM pg_stat_activity WHERE query LIKE 'CREATE INDEX CONCURRENTLY%' AND wait_event_type = 'Lock'") == 1);
            execute("SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE query LIKE 'CREATE INDEX CONCURRENTLY%'");
            builder.join();
            assertThat((Throwable) failure.get()).isNotNull();
            assertThat(failure.get().getSQLState()).isEqualTo("57014");
            blocker.rollback();
        }
        assertThat(validity(tables)).as("the cancelled build left an invalid index").isFalse();

        var indexed = eventLog(EventLogSettings.defaults().withEventTypeIndexed(Set.of(TYPE)), SchemaOwnership.COMPONENT);
        indexed.registerAggregateType(TYPE);
        assertThat(indexed.eventTypeIndex().build(TYPE).get(60, TimeUnit.SECONDS)).isTrue();
        assertThat(validity(tables)).isTrue();
    }

    @Test
    void where_the_engine_does_not_create_the_schema_nothing_is_built_and_the_statement_is_logged() throws Exception {
        var plain  = startedEventLog(TYPE);
        var tables = plain.registerAggregateType(TYPE);
        var logger   = (Logger) LoggerFactory.getLogger(EventTypeIndex.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var settings = EventLogSettings.defaults().withEventTypeIndexed(Set.of(TYPE));
            var external = new EventLog(dataSource, SchemaOwnership.COMPONENT, EventLogSchema.lockedAggregateTypeDdl(dataSource), settings, EventLogObserver.NONE, () -> false);
            external.start();
            external.registerAggregateType(TYPE);
            assertThat((Object) external.eventTypeIndex().build(TYPE)).as("no build started").isNull();
            assertThat(external.eventTypeIndex().valid(TYPE)).isFalse();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(validity(tables)).as("absent").isNull();
        var create = EventLogSchemaContributor.eventTypeIndexStatements(tables).getFirst();
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(create));
    }

    private EventLog eventLog(EventLogSettings settings, SchemaOwnership ownership) throws SQLException {
        var log = EventLog.builder().setDataSource(dataSource).setSchemaOwnership(ownership).setSettings(settings).build();
        log.start();
        return log;
    }

    private void seed(EventLog log, int rows) throws SQLException {
        for (var done = 0; done < rows; done += 500) {
            var requests = new ArrayList<AppendRequest>();
            for (var a = 0; a < 5; a++) {
                var events = new ArrayList<EventToAppend>();
                for (var i = 0; i < 100; i++) {
                    events.add(event("T" + (i % 20), "{\"n\":" + i + "}"));
                }
                requests.add(AppendRequest.withoutExpectedOrder(TYPE, "agg-" + (done / 500 * 5 + a), events));
            }
            appendCommitted(log, requests.toArray(AppendRequest[]::new));
        }
    }

    /** @return {@code indisvalid AND indisready}, or {@code null} if the index is absent */
    private Boolean validity(AggregateTypeTables tables) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.INDEX_VALIDITY)) {
            statement.setString(1, tables.eventTypeIndex());
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getBoolean(1) : null;
            }
        }
    }
}
