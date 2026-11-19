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

import org.postgresql.ds.PGSimpleDataSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The child JVM of {@code EventLogForkedTakeoverIT}, after {@code spike/SpikeWriterProcess}'s
 * pattern: same classpath, arguments on the command line ({@code jdbcUrl user password nodeName}), a line protocol on
 * standard output, and it ends - stopping its subscriptions, which releases their leases - when its standard input
 * closes. It runs one periodic {@code FRESH} and one in-transaction {@code CONTIGUOUS} exclusive subscription of
 * {@link #TYPE}, the latter writing {@code (global_order, nodeName)} into {@code el_test_view}, on the
 * test lease timings. Its connections carry {@code application_name = nodeName}.
 * <ul>
 *     <li>{@code C <subscriber> <fence>} on a claim</li>
 *     <li>{@code H <global_order> <fence> <nanoTime>} per event the periodic subscription handled</li>
 *     <li>{@code L <subscriber>} on a loss</li>
 * </ul>
 * {@code System.nanoTime()} is {@code CLOCK_MONOTONIC} on Linux, shared by every process on the host.
 * <p>
 * The takeover measurement ({@code spike.EventLogGateSpikeIT#takeoverTime}) starts it with four more arguments
 * ({@code default <periodicSubscriber> <pagesSubscriber> <startFrom>}, through {@link GateSpikeAccess#startLeaseOwner}):
 * the default lease settings ({@link EventLogSettings#defaults()}) on a small pool instead of the test timings, the given
 * subscriber names and start point, and one more line, {@code P <global_order> <nanoTime>} per event the in-transaction
 * handler wrote (before its page commits). Without them it behaves as the IT expects.
 */
final class LeaseOwnerProcess {
    static final String PERIODIC = "forked-periodic";
    static final String PAGES    = "forked-pages";
    static final String VIEW     = "el_test_view";
    static final String TYPE     = "Orders";
    /**
     * {@link AbstractLeaseIT#LEASE}'s timings, repeated here so the child never loads the IT classes (their static
     * initializer builds a container)
     */
    static final EventLogSettings LEASE = EventLogSettings.defaults()
                                                          .withBackstopInterval(java.time.Duration.ofMillis(25))
                                                          .withProbeBackoff(java.time.Duration.ofMillis(25), java.time.Duration.ofMillis(100))
                                                          .withProgressSaveInterval(java.time.Duration.ZERO)
                                                          .withLease(java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(1))
                                                          .withLeaseStatementTimeout(java.time.Duration.ofSeconds(1));

    private LeaseOwnerProcess() {
    }

    /** One line of the child's protocol */
    record Line(String kind, String[] fields, long receivedNanos) {
    }

    /** Starts a child; every line it prints goes to {@code lines} */
    static Process start(String jdbcUrl, String user, String password, String nodeName, List<Line> lines) throws IOException {
        var java    = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LeaseOwnerProcess.class.getName(), jdbcUrl, user, password, nodeName)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Thread.ofPlatform().daemon().start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    var fields = line.split(" ");
                    if (fields.length >= 2) {
                        lines.add(new Line(fields[0], fields, System.nanoTime()));
                    }
                }
            } catch (IOException e) {
                // child ended
            }
        });
        return process;
    }

    /** Graceful: close the child's standard input and wait for it to end */
    static void stop(Process process) throws Exception {
        process.getOutputStream().close();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }

    public static void main(String[] args) throws Exception {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(args[0]);
        dataSource.setUser(args[1]);
        dataSource.setPassword(args[2]);
        dataSource.setApplicationName(args[3]);
        var nodeName = args[3];
        var gate     = args.length >= 8 && args[4].equals("default");
        var periodicName = gate ? args[5] : PERIODIC;
        var pagesName    = gate ? args[6] : PAGES;
        var startFrom    = gate ? Long.parseLong(args[7]) : 1L;
        javax.sql.DataSource source = dataSource;
        if (gate) {
            var config = new com.zaxxer.hikari.HikariConfig();
            config.setDataSource(dataSource);
            config.setMaximumPoolSize(10);
            source = new com.zaxxer.hikari.HikariDataSource(config);
        }
        var out      = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        var observer = new EventLogObserver() {
            @Override
            public void onLeaseClaimed(String subscriber, String aggregateType, long fence) {
                synchronized (out) {
                    out.println("C " + subscriber + " " + fence);
                }
            }

            @Override
            public void onLeaseLost(String subscriber, String aggregateType, String reason) {
                synchronized (out) {
                    out.println("L " + subscriber);
                }
            }
        };
        var log = EventLog.builder().setDataSource(source).setSettings((gate ? EventLogSettings.defaults() : LEASE).withNodeName(nodeName)).setObserver(observer).build();
        log.start();
        log.registerAggregateType(TYPE);
        var periodicRef = new AtomicReference<SubscriptionHandle>();
        var periodic = log.subscribe(Definitions.definition(periodicName, TYPE, null, startFrom, null, null), event -> {
            var subscription = periodicRef.get();
            Long fence        = subscription == null ? null : Definitions.internal(subscription).lease().fenceValue();
            synchronized (out) {
                out.println("H " + event.globalOrder() + " " + fence + " " + System.nanoTime());
            }
        });
        periodicRef.set(periodic);
        var pages = log.subscribe(Definitions.definition(pagesName, TYPE, null, startFrom, ConsistencyMode.CONTIGUOUS, null),
                                           PageHandler.perEvent((event, tx) -> {
                                               var connection = tx.connection();
                                               try (var statement = connection.prepareStatement("INSERT INTO " + VIEW + " (global_order, writer) VALUES (?, ?)")) {
                                                   statement.setLong(1, event.globalOrder());
                                                   statement.setString(2, nodeName);
                                                   statement.executeUpdate();
                                               }
                                               if (gate) {
                                                   synchronized (out) {
                                                       out.println("P " + event.globalOrder() + " " + System.nanoTime());
                                                   }
                                               }
                                           }));
        // ends when the parent closes our standard input
        while (System.in.read() != -1) {
            // drain
        }
        periodic.stop();
        pages.stop();
        log.stop();
        System.exit(0);
    }
}
