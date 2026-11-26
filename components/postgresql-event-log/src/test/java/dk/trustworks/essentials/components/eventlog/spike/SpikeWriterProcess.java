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

package dk.trustworks.essentials.components.eventlog.spike;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.eventlog.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The forked-JVM check of the "two engines in one JVM" shortcut: the writer engine runs in a child JVM on the
 * same classpath, appends one event per unit of work at the given rate, calls {@code afterCommit} (tier 1 on), and
 * prints {@code B <id> <nanoTime>} before and {@code C <id> <nanoTime>} after each commit. {@code System.nanoTime()} is
 * {@code CLOCK_MONOTONIC} on Linux, shared by every process on the host, so the parent compares it with its own. The
 * child ends when its standard input closes. Spikes only.
 */
final class SpikeWriterProcess {
    private static final String TYPE       = "Orders";
    private static final String EVENT_TYPE = "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent$OrderAdded";

    private SpikeWriterProcess() {
    }

    static Process start(SpikeDb db, int rate, Map<String, Long> committed, AtomicBoolean measuring, AtomicLong appended) throws IOException {
        var java    = Path(System.getProperty("java.home"), "bin", "java");
        var process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), SpikeWriterProcess.class.getName(),
                                         db.jdbcUrl(), db.role("writer"), db.password("writer"), Integer.toString(rate))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Thread.ofPlatform().daemon().start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    var f = line.split(" ");
                    if (f.length != 3 || !(f[0].equals("B") || f[0].equals("C"))) continue;
                    if (!measuring.get()) continue;
                    committed.put(f[1], Long.parseLong(f[2]));
                    if (f[0].equals("C")) appended.incrementAndGet();
                }
            } catch (IOException e) {
                // child ended
            }
        });
        return process;
    }

    private static String Path(String first, String... more) {
        return java.nio.file.Path.of(first, more).toString();
    }

    static void stop(Process process) throws Exception {
        process.getOutputStream().close();
        if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
    }

    public static void main(String[] args) throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(args[0]);
        config.setUsername(args[1]);
        config.setPassword(args[2]);
        config.setMaximumPoolSize(4);
        int rate = Integer.parseInt(args[3]);
        try (var pool = new HikariDataSource(config)) {
            var log = EventLog.builder().setDataSource(pool).setSettings(EventLogSettings.defaults().withCrossJvmWakeUp(true)).build();
            log.start();
            log.registerAggregateType(TYPE);
            log.eventTypes().idOf(EVENT_TYPE);
            var out       = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            var cause     = UUID.randomUUID();
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                try (var c = pool.getConnection()) {
                    var id = UUID.randomUUID().toString();
                    c.setAutoCommit(false);
                    log.append(c, AppendRequest.withoutExpectedOrder(TYPE, id, List.of(new EventToAppend(UUID.randomUUID(), cause, cause, EVENT_TYPE, 1, Instant.now(),
                                                                                                        "{\"orderId\":\"" + id + "\"}", "{\"Key1\":\"Value1\"}"))));
                    synchronized (out) {
                        out.println("B " + id + " " + System.nanoTime());
                    }
                    c.commit();
                    long at = System.nanoTime();
                    synchronized (out) {
                        out.println("C " + id + " " + at);
                    }
                    log.afterCommit(List.of(TYPE));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 0, 1_000_000 / rate, TimeUnit.MICROSECONDS);
            // ends when the parent closes our standard input
            while (System.in.read() != -1) {
                // drain
            }
            scheduler.shutdown();
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            log.stop();
        }
        System.exit(0);
    }
}
