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

package dk.trustworks.essentials.components.eventlog.processor;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.Added;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared setup of the processor ITs (design phase 8 §9.2): one static container, a fresh schema per test, the engine on
 * its own data source, the application's pool behind a JDBI unit-of-work factory and a Spring transaction manager, and
 * 100 {@link Added} events over 10 orders ({@code o0}..{@code o9}, round robin; global order {@code n} adds {@code n}).
 */
@Testcontainers
abstract class AbstractProcessorIT {
    static final String ORDERS = "Orders";
    static final int    EVENTS = 100;

    @Container
    static final PostgreSQLContainer postgres = EssentialsTestContainers.postgres();

    protected PGSimpleDataSource         engineDataSource;
    protected HikariDataSource           pool;
    protected Jdbi                       jdbi;
    protected JdbiUnitOfWorkFactory      unitOfWorkFactory;
    protected DataSourceTransactionManager transactionManager;
    protected JSONEventSerializer        serializer;
    protected EventLog                   eventLog;

    /** Where a test's page transactions come from */
    enum SourceKind {
        JDBI,
        SPRING
    }

    @BeforeEach
    void setUpDatabase() throws SQLException {
        engineDataSource = new PGSimpleDataSource();
        engineDataSource.setUrl(postgres.getJdbcUrl());
        engineDataSource.setUser(postgres.getUsername());
        engineDataSource.setPassword(postgres.getPassword());
        execute("DROP SCHEMA public CASCADE");
        execute("CREATE SCHEMA public");
        execute("CREATE TABLE view_totals (aggregate_id text PRIMARY KEY, total bigint NOT NULL)");
        execute("CREATE TABLE applied (global_order bigint PRIMARY KEY, n int NOT NULL)");
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(10);
        pool = new HikariDataSource(config);
        jdbi = Jdbi.create(pool);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        transactionManager = new DataSourceTransactionManager(pool);
        serializer = EssentialsJSONEventSerializers.create();
        eventLog = EventLog.builder()
                           .setDataSource(engineDataSource)
                           .setSettings(EventLogSettings.defaults().withLease(Duration.ofSeconds(3), Duration.ofMillis(300), Duration.ofSeconds(2)))
                           .build();
        eventLog.start();
        eventLog.registerAggregateType(ORDERS);
    }

    @AfterEach
    void tearDown() {
        if (eventLog != null) {
            eventLog.stop();
        }
        if (pool != null) {
            pool.close();
        }
    }

    static ErrorSettings errors() {
        return new ErrorSettings(2, Duration.ofMillis(10), Duration.ofMillis(50), 100);
    }

    PageTransactionSource source(SourceKind kind, Set<ProcessorMutant> mutants) {
        return switch (kind) {
            case JDBI -> new UnitOfWorkPageTransactions(unitOfWorkFactory, jdbi, mutants);
            case SPRING -> new SpringPageTransactions(transactionManager, pool, mutants);
        };
    }

    /** Seams that subscribe through the engine's package-private path, with its mutants and a crash point */
    static ProcessorSeams seams(Set<ProcessorMutant> mutants, Set<String> engineMutants, ProcessorTestBridge.CrashPoint crashAt, AtomicBoolean crashed) {
        return new ProcessorSeams(mutants, (log, definition, handler, source) ->
                ProcessorTestBridge.subscribe(log, definition, handler, source, engineMutants, crashAt, () -> true, crashed));
    }

    static ProcessorSeams seams(Set<ProcessorMutant> mutants, Set<String> engineMutants) {
        return seams(mutants, engineMutants, null, new AtomicBoolean());
    }

    static String orderOf(long globalOrder) {
        return "o" + ((globalOrder - 1) % 10);
    }

    /**
     * Append {@link #EVENTS} events, one per append, in one transaction; global order {@code n} is on {@link #orderOf(long)}
     * and adds {@code n}, except {@code unknownAt}, whose payload class does not exist
     */
    void appendEvents(long unknownAt) throws SQLException {
        try (var connection = engineDataSource.getConnection()) {
            connection.setAutoCommit(false);
            for (long n = 1; n <= EVENTS; n++) {
                var order = orderOf(n);
                var type  = n == unknownAt ? "FQCN:dk.trustworks.essentials.components.eventlog.processor.test_data.Gone" : "FQCN:" + Added.class.getName();
                var event = new EventToAppend(UUID.randomUUID(), null, null, type, 1, Instant.now(), serializer.serialize(new Added(order, (int) n)),
                                              serializer.serialize(EventMetaData.empty()));
                eventLog.append(connection, AppendRequest.withoutExpectedOrder(ORDERS, order, List.of(event)));
            }
            connection.commit();
        }
    }

    /** Append one event of {@code payload}'s class to {@code aggregateType}'s stream {@code aggregateId}, committed at once */
    void appendOne(String aggregateType, String aggregateId, Object payload) throws SQLException {
        try (var connection = engineDataSource.getConnection()) {
            var event = new EventToAppend(UUID.randomUUID(), null, null, "FQCN:" + payload.getClass().getName(), 1, Instant.now(), serializer.serialize(payload),
                                          serializer.serialize(EventMetaData.empty()));
            eventLog.append(connection, AppendRequest.withoutExpectedOrder(aggregateType, aggregateId, List.of(event)));
        }
    }

    /** The reference fold: per order, the sum of every applied global order */
    static Map<String, Long> fold(Set<Long> excluded) {
        var totals = new TreeMap<String, Long>();
        for (long n = 1; n <= EVENTS; n++) {
            if (!excluded.contains(n)) {
                totals.merge(orderOf(n), n, Long::sum);
            }
        }
        return totals;
    }

    /** The global orders of {@code globalOrder}'s order from it on: what parking it holds back */
    static Set<Long> tailOf(long globalOrder) {
        var tail = new TreeSet<Long>();
        for (long n = globalOrder; n <= EVENTS; n += 10) {
            tail.add(n);
        }
        return tail;
    }

    /** Apply {@code event} to the view and count it, on the page transaction's connection */
    static void applyToView(Connection connection, DeliveredEvent event) throws SQLException {
        var added = event.payloadAs(Added.class);
        try (var statement = connection.prepareStatement("INSERT INTO view_totals VALUES (?, ?) ON CONFLICT (aggregate_id) DO UPDATE SET total = view_totals.total + excluded.total")) {
            statement.setString(1, added.orderId());
            statement.setLong(2, added.amount());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("INSERT INTO applied VALUES (?, 1) ON CONFLICT (global_order) DO UPDATE SET n = applied.n + 1")) {
            statement.setLong(1, event.globalOrder().longValue());
            statement.executeUpdate();
        }
    }

    Map<String, Long> view() throws SQLException {
        var view = new TreeMap<String, Long>();
        try (var connection = engineDataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT aggregate_id, total FROM view_totals")) {
            while (rows.next()) {
                view.put(rows.getString(1), rows.getLong(2));
            }
        }
        return view;
    }

    /** global order -> times applied */
    Map<Long, Integer> applied() throws SQLException {
        var applied = new TreeMap<Long, Integer>();
        try (var connection = engineDataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT global_order, n FROM applied")) {
            while (rows.next()) {
                applied.put(rows.getLong(1), rows.getInt(2));
            }
        }
        return applied;
    }

    long resumePoint(ProgressKey key) throws SQLException {
        return eventLog.admin().subscription(key).map(SubscriptionRow::resumePoint).orElse(0L);
    }

    List<ParkedRow> parked(ProgressKey key) throws SQLException {
        return eventLog.admin().parked(key, 0, 1000).rows();
    }

    /** Wait until the subscription's committed resume point is past every event */
    void awaitCaughtUp(ProgressKey key) {
        awaitTrue("caught up past " + EVENTS, Duration.ofSeconds(20), () -> resumePoint(key) > EVENTS);
    }

    /** Polls {@code condition}; an AssertionError (never a timeout exception) when it does not hold in time */
    static void awaitTrue(String what, Duration atMost, Callable<Boolean> condition) {
        var deadline = System.nanoTime() + atMost.toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.call()) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for: " + what, last);
    }

    void execute(String sql) throws SQLException {
        try (var connection = engineDataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
