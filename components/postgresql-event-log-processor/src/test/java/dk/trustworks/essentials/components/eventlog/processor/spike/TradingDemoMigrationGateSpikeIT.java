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

package dk.trustworks.essentials.components.eventlog.processor.spike;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.EventLogEventStore;
import dk.trustworks.essentials.components.eventlog.migration.cli.MigrationCli;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration measurement on trading-demo data, at harness level (no demo
 * change). The source is a copy ({@code CREATE DATABASE … TEMPLATE …}) of a database the trading demo wrote on the
 * old engine; this test runs the migration CLI against it in-process and then checks, through {@link EventLogEventStore}
 * and new-engine subscriptions, that the result resumes without loss.
 * <ul>
 *     <li>{@code -Dspike.demo.phase=online}: {@code preflight}, {@code prepare}, {@code copy --until-caught-up} repeated for
 *     {@code -Dspike.demo.online.seconds} (default 120) while the demo writes, then {@code verify}</li>
 *     <li>{@code -Dspike.demo.phase=cutover}, with every demo instance stopped: {@code import-preview}, {@code cutover}
 *     with an import of every {@code durable_subscriptions} row, {@code verify --after-cutover}, {@code verify --all}, then
 *     the resume checks of step 4</li>
 * </ul>
 * Connection: {@code -Dspike.demo.url} (jdbc url of the copy), {@code -Dspike.demo.user}, {@code -Dspike.demo.password}.
 * Results go to {@code target/spike-results/p9-g-demo.txt}. Throwaway: runs only with {@code -Dspike.demo.phase} set.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.demo.phase", matches = "online|cutover")
class TradingDemoMigrationGateSpikeIT {
    private static final String       RESULTS  = "p9-g-demo.txt";
    private static final String       PHASE    = System.getProperty("spike.demo.phase");
    private static final String       URL      = System.getProperty("spike.demo.url", "jdbc:postgresql://172.18.0.2:5432/p9g_demo_copy");
    private static final String       USER     = System.getProperty("spike.demo.user", "essentials");
    private static final String       PASSWORD = System.getProperty("spike.demo.password", "password");
    private static final List<String> TYPES    = List.of("Accounts", "BankAccountLinks", "Deposits", "Instruments", "Orders", "ReconciliationCases", "TradeSettlements",
                                                         "UnknownExchangeOrders", "Users", "Withdrawals");

    private PGSimpleDataSource dataSource;

    @BeforeEach
    void connect() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(URL);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
    }

    @Test
    void tradingDemoMigration() throws Exception {
        var config = writeConfig();
        if (PHASE.equals("online")) {
            online(config);
        } else {
            cutover(config);
        }
    }

    // ------------------------------------------------------------------ step 2: online, under the demo's writes

    private void online(Path config) throws Exception {
        SpikeDb.record(RESULTS, "=== ONLINE " + Instant.now() + " " + URL);
        assertThat(cli("preflight", config)).isEqualTo(MigrationCli.OK);
        assertThat(cli("prepare", config)).isEqualTo(MigrationCli.OK);
        long until  = System.nanoTime() + Long.getLong("spike.demo.online.seconds", 120) * 1_000_000_000L;
        int  rounds = 0;
        do {
            assertThat(cli("copy", config, "--until-caught-up", "--max-delta", "500")).isEqualTo(MigrationCli.OK);
            rounds++;
            Thread.sleep(5_000);
        } while (System.nanoTime() < until);
        SpikeDb.record(RESULTS, "online copy rounds: " + rounds);
        assertThat(cli("verify", config)).isEqualTo(MigrationCli.OK);
        assertThat(cli("status", config)).isEqualTo(MigrationCli.OK);
    }

    // ------------------------------------------------------------------ steps 2-4: the stop, the cut-over and the resume checks

    private void cutover(Path config) throws Exception {
        SpikeDb.record(RESULTS, "=== CUTOVER " + Instant.now() + " " + URL);
        assertThat(cli("copy", config, "--until-caught-up")).isEqualTo(MigrationCli.OK);
        assertThat(cli("import-preview", config)).isEqualTo(MigrationCli.OK);
        long t = System.nanoTime();
        assertThat(cli("cutover", config)).isEqualTo(MigrationCli.OK);
        SpikeDb.record(RESULTS, String.format("cutover command wall time (incl. JVM-side event log start): %.0f ms", (System.nanoTime() - t) / 1e6));
        assertThat(cli("verify", config, "--after-cutover")).isEqualTo(MigrationCli.OK);
        assertThat(cli("verify", config, "--all")).isEqualTo(MigrationCli.OK);
        assertThat(cli("status", config)).isEqualTo(MigrationCli.OK);
        resumeChecks();
    }

    private void resumeChecks() throws Exception {
        var mapper   = EssentialsObjectMappers.createJackson3ObjectMapper();
        var eventLog = EventLog.builder().setDataSource(dataSource).build();
        eventLog.start();
        try {
            var jdbi = Jdbi.create(dataSource);
            jdbi.installPlugin(new PostgresPlugin());
            EventLogEventStore.registerJdbiTypes(jdbi);
            var uow = new EventStoreManagedUnitOfWorkFactory(jdbi);
            var store = new EventLogEventStore(eventLog, uow, EssentialsJSONEventSerializers.create(), (id, cfg, event, order) -> {
                throw new UnsupportedOperationException("the harness appends through the event log");
            });

            // (a) every copied stream loads through the adapter; payloads compared as Jackson trees with the old table's
            var newOrders = new HashMap<String, Long>();
            for (var type : TYPES) {
                var aggregateType = AggregateType.of(type);
                store.addAggregateEventStreamConfiguration(aggregateType, String.class);
                var table   = type.toLowerCase(Locale.ROOT) + "_events";
                var old     = oldRows(table);
                var streams = new TreeMap<String, List<OldRow>>();
                old.forEach(r -> streams.computeIfAbsent(r.aggregateId, k -> new ArrayList<>()).add(r));
                long events = 0;
                for (var stream : streams.entrySet()) {
                    List<PersistedEvent> loaded = uow.withUnitOfWork(u -> store.fetchStream(aggregateType, stream.getKey()).orElseThrow().eventList());
                    assertThat(loaded).as(type + "/" + stream.getKey()).hasSize(stream.getValue().size());
                    for (int i = 0; i < loaded.size(); i++) {
                        var e = loaded.get(i);
                        var o = stream.getValue().get(i);
                        assertThat(e.globalEventOrder().longValue()).isEqualTo(o.globalOrder);
                        assertThat(e.eventOrder().longValue()).isEqualTo(o.eventOrder);
                        assertThat(e.eventId().toString()).isEqualTo(o.eventId);
                        assertThat(mapper.readTree(e.event().getJson())).as(type + " payload @" + o.globalOrder).isEqualTo(mapper.readTree(o.payload));
                        events++;
                    }
                }
                SpikeDb.record(RESULTS, String.format("LOAD %-22s %6d streams, %7d events loaded through EventLogEventStore, payload trees equal", type, streams.size(), events));

                // (b) one appended event, built from a copied payload, with a new event id
                long s = old.stream().mapToLong(r -> r.globalOrder).max().orElse(0);
                try (var c = dataSource.getConnection()) {
                    c.setAutoCommit(false);
                    AppendResult result;
                    long expectedOrder;
                    if (old.isEmpty()) {
                        expectedOrder = 0;
                        result = eventLog.append(c, AppendRequest.afterEventOrder(type, "P9G-NEW-1", AppendRequest.NEW_STREAM,
                                                                                    List.of(new EventToAppend(UUID.randomUUID(), null, UUID.randomUUID(), "P9GProbe", 1, Instant.now(), "{\"probe\":true}", "{}"))));
                    } else {
                        var last = old.getLast();
                        var head = streams.get(last.aggregateId).getLast();
                        expectedOrder = head.eventOrder + 1;
                        result = eventLog.append(c, AppendRequest.afterEventOrder(type, head.aggregateId, head.eventOrder,
                                                                                    List.of(new EventToAppend(UUID.randomUUID(), null, UUID.randomUUID(), head.eventType, head.revision,
                                                                                                              Instant.now(), head.payload, head.metadata))));
                    }
                    c.commit();
                    eventLog.afterCommit(List.of(type));
                    var appended = result.events().getFirst();
                    SpikeDb.record(RESULTS, String.format("APPEND %-22s event_order %d (expected %d), global_order %d (old max S %d)", type, appended.eventOrder(), expectedOrder,
                                                          appended.globalOrder(), s));
                    assertThat(appended.eventOrder()).isEqualTo(expectedOrder);
                    assertThat(appended.globalOrder()).isGreaterThan(s);
                    newOrders.put(type, appended.globalOrder());
                }
            }

            // (c) every imported subscription delivers every event at or above its I, plus the new one
            var progress = new ArrayList<Object[]>();
            try (var c = dataSource.getConnection(); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT subscriber, aggregate_type, consistency_mode, progress_model, resume_from FROM el_progress ORDER BY aggregate_type, subscriber")) {
                while (rs.next()) progress.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)});
            }
            SpikeDb.record(RESULTS, "imported progress rows: " + progress.size());
            var handles = new ArrayList<SubscriptionHandle>();
            var checks  = new ArrayList<Runnable>();
            for (var p : progress) {
                String subscriber = (String) p[0], type = (String) p[1];
                long   from       = (Long) p[4];
                var definition = SubscriptionDefinition.builder(subscriber, type).consistency(ConsistencyMode.valueOf((String) p[2])).exclusive().build();
                var delivered  = new ConcurrentLinkedQueue<Long>();
                handles.add(p[3].equals("PERIODIC") ? eventLog.subscribe(definition, (PeriodicHandler) e -> delivered.add(e.globalOrder()))
                                                    : eventLog.subscribe(definition, (PageHandler) (page, tx) -> page.forEach(e -> delivered.add(e.globalOrder()))));
                var expected = new TreeSet<Long>();
                oldRows(type.toLowerCase(Locale.ROOT) + "_events").stream().filter(r -> r.globalOrder >= from).forEach(r -> expected.add(r.globalOrder));
                expected.add(newOrders.get(type));
                checks.add(() -> {
                    long deadline = System.nanoTime() + 120_000_000_000L;
                    while (System.nanoTime() < deadline && !new TreeSet<>(delivered).containsAll(expected)) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    var got = new ArrayList<>(delivered);
                    var set = new TreeSet<>(got);
                    SpikeDb.record(RESULTS, String.format("DELIVER %-50s %-22s %s/%s I=%d expected %d delivered %d (distinct %d) equal %s", subscriber, type, p[2], p[3], from,
                                                          expected.size(), got.size(), set.size(), set.equals(expected)));
                    assertThat(set).as(subscriber).isEqualTo(expected);
                });
            }
            checks.forEach(Runnable::run);
            handles.forEach(SubscriptionHandle::stop);
        } finally {
            eventLog.stop();
        }
    }

    record OldRow(String aggregateId, long globalOrder, long eventOrder, String eventId, String eventType, int revision, String payload, String metadata) {
    }

    private List<OldRow> oldRows(String table) throws SQLException {
        var rows = new ArrayList<OldRow>();
        try (var c = dataSource.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT aggregate_id, global_order, event_order, event_id, event_type, event_revision, event_payload::text, event_metadata::text FROM " + table
                                              + " ORDER BY aggregate_id, event_order")) {
            while (rs.next()) {
                rows.add(new OldRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), Integer.parseInt(rs.getString(6)), rs.getString(7), rs.getString(8)));
            }
        }
        rows.sort(Comparator.comparingLong(OldRow::globalOrder));
        return rows;
    }

    // ------------------------------------------------------------------ the CLI

    private int cli(String command, Path config, String... options) {
        var out  = new ByteArrayOutputStream();
        var args = new ArrayList<String>();
        args.add(command);
        args.add("--config");
        args.add(config.toString());
        args.addAll(List.of(options));
        long t    = System.nanoTime();
        int  code = new MigrationCli(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(out, true, StandardCharsets.UTF_8)).run(args.toArray(String[]::new));
        SpikeDb.record(RESULTS, String.format("--- %s (exit %d, %.0f ms)%n%s", String.join(" ", args.subList(0, 1)) + " " + String.join(" ", options), code, (System.nanoTime() - t) / 1e6,
                                              out.toString(StandardCharsets.UTF_8).stripTrailing()));
        return code;
    }

    /** The YAML the operator writes: every table, and every durable_subscriptions row mapped by the old processor's kind */
    private Path writeConfig() throws Exception {
        var y = new StringBuilder();
        y.append("connection:\n  url: ").append(URL).append("\n  user: ").append(USER).append("\n  password: ").append(PASSWORD).append("\n");
        y.append("tables:\n");
        for (var type : TYPES) y.append("  - aggregateType: ").append(type).append("\n    table: ").append(type.toLowerCase(Locale.ROOT)).append("_events\n");
        y.append("settings:\n  epochWaitTimeout: 60s\n");
        y.append("import:\n  subscriptions:\n");
        try (var c = dataSource.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT subscriber_id, aggregate_type FROM durable_subscriptions ORDER BY aggregate_type, subscriber_id")) {
            while (rs.next()) {
                var id   = rs.getString(1);
                var view = id.substring(0, id.indexOf(':')).endsWith("Projection");
                y.append("    - oldSubscriber: \"").append(id).append("\"\n      subscriber: \"").append(id).append("\"\n      aggregateType: ").append(rs.getString(2))
                 .append("\n      model: ").append(view ? "IN_HANDLER_TRANSACTION" : "PERIODIC").append("\n      consistency: ").append(view ? "CONTIGUOUS" : "FRESH").append("\n");
            }
        }
        var file = Path.of("target", "spike-results", "p9-g-demo-migration.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, y);
        return file;
    }
}
