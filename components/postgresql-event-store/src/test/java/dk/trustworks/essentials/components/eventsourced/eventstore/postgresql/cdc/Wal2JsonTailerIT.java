/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.Wal2JsonTailerProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.handler.Wal2JsonTailerErrorHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class Wal2JsonTailerIT extends AbstractWal2JsonPostgresIT {

    private CdcInboxRepository inboxRepository;

    private static final Pattern INSERT_KIND   = Pattern.compile("\"kind\"\\s*:\\s*\"insert\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TS_IN_PAYLOAD = Pattern.compile("ts=(\\d+)");

    public static final String TEST_TABLE = "cdc_test_events";

    @BeforeEach
    void setup() {
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);

        jdbi.useHandle(h -> {
            h.execute("drop table if exists " + TEST_TABLE);
            h.execute(bind("""
                               create table {:tableName} (
                                   id bigserial primary key,
                                   payload text not null
                               )
                           """, arg("tableName", TEST_TABLE)));
        });

        jdbi.useHandle(h -> {
            String slot = "probe_wal2json_" + UUID.randomUUID().toString().replace("-", "");
            h.execute("select * from pg_create_logical_replication_slot(?, 'wal2json')", slot);
            h.execute("select pg_drop_replication_slot(?)", slot);
        });

        inboxRepository = new CdcInboxRepository(unitOfWorkFactory);
        inboxRepository.createTableAndIndexes();

        truncateInbox();
    }

    void truncateInbox() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("truncate table " + CdcSql.DEFAULT_CDC_TABLE_NAME + " cascade");
        });
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(uow -> uow.rollback(new RuntimeException("test-cleanup")));
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void starts_and_persists_wal2json_messages_for_inserts() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");

        var tailer = new Wal2JsonTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                Wal2JsonTailerProperties.defaults(
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(100)
                                                 ),
                PgSlotMode.CREATE_IF_MISSING,
                Optional.empty(),
                Optional.empty()
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        assertThat(tailer.isStarted()).isTrue();

        String payload = "payload-" + ThreadLocalRandom.current().nextInt(1_000_000);
        jdbi.useHandle(h -> h.execute("insert into " + TEST_TABLE + "(payload) values(?)", payload));

        Awaitility.await()
                  .atMost(Duration.ofSeconds(10))
                  .pollInterval(Duration.ofMillis(100))
                  .untilAsserted(() -> {
                      assertThat(inboxRepository.countByStatus(slotName, CdcInboxRepository.InboxStatus.RECEIVED.name()))
                              .isGreaterThanOrEqualTo(1);

                      // Optional stronger assertion: payload appears in at least one stored WAL message
                      assertThat(anyInboxPayloadContains(slotName, payload)).isTrue();
                  });

        tailer.stop();
        assertThat(tailer.isStarted()).isFalse();
    }


    @Test
    void stops_and_does_not_persist_after_stop() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");

        var tailer = new Wal2JsonTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                Wal2JsonTailerProperties.defaults(
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(100)
                                                 ),
                PgSlotMode.CREATE_IF_MISSING,
                Optional.empty(),
                Optional.empty()
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));

        String payload1 = "payload-1-" + UUID.randomUUID();
        jdbi.useHandle(h -> h.execute("insert into " + TEST_TABLE + "(payload) values(?)", payload1));

        Awaitility.await()
                  .atMost(Duration.ofSeconds(10))
                  .untilAsserted(() ->
                                         assertThat(inboxRepository.countByStatus(slotName, CdcInboxRepository.InboxStatus.RECEIVED.name()))
                                                 .isGreaterThanOrEqualTo(1)
                                );

        tailer.stop();
        assertThat(tailer.isStarted()).isFalse();

        String payload2 = "payload-2-" + UUID.randomUUID();
        jdbi.useHandle(h -> h.execute("insert into " + TEST_TABLE + "(payload) values(?)", payload2));

        Awaitility.await()
                  .during(Duration.ofSeconds(3))
                  .atMost(Duration.ofSeconds(4))
                  .untilAsserted(() ->
                                         assertThat(anyInboxPayloadContains(slotName, payload2))
                                                 .as("payload inserted after stop must not appear in CDC inbox")
                                                 .isFalse()
                                );
    }

    @Test
    void retries_after_transient_inbox_failure_and_eventually_persists() {
        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");

        AtomicInteger failuresLeft = new AtomicInteger(1);

        CdcInboxRepository flakyInbox = new CdcInboxRepository(unitOfWorkFactory) {
            @Override
            public boolean insertIfAbsent(String slot, String lsn, String payloadJson) {
                if (failuresLeft.getAndDecrement() > 0) {
                    throw new RuntimeException("Simulated inbox write failure");
                }
                return inboxRepository.insertIfAbsent(slot, lsn, payloadJson);
            }

            @Override
            public void createTableAndIndexes() {
                inboxRepository.createTableAndIndexes();
            }

            @Override
            public long countByStatus(String slot, String status) {
                return inboxRepository.countByStatus(slot, status);
            }
        };

        var handler = new Wal2JsonTailerErrorHandler() {
            @Override
            public Decision onMessageError(String slot, String json, Exception error) {
                // Inbox failure should cause reconnect/retry (do NOT ack)
                return Decision.RETRY_CONNECTION;
            }

            @Override
            public Decision onStreamError(String slot, Exception error) {
                return Decision.RETRY_CONNECTION;
            }
        };

        var tailer = new Wal2JsonTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                flakyInbox,
                Wal2JsonTailerProperties.defaults(
                        Duration.ofMillis(20),
                        Duration.ofMillis(50),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(100)
                                                 ),
                PgSlotMode.CREATE_IF_MISSING,
                Optional.empty(),
                Optional.of(handler)
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));

        String payload = "payload-retry-" + UUID.randomUUID();
        jdbi.useHandle(h -> h.execute("insert into " + TEST_TABLE + "(payload) values(?)", payload));

        Awaitility.await()
                  .atMost(Duration.ofSeconds(15))
                  .pollInterval(Duration.ofMillis(100))
                  .untilAsserted(() -> {
                      assertThat(inboxRepository.countByStatus(slotName, CdcInboxRepository.InboxStatus.RECEIVED.name()))
                              .isGreaterThanOrEqualTo(1);
                      assertThat(anyInboxPayloadContains(slotName, payload)).isTrue();
                  });

        assertThat(failuresLeft.get()).isLessThanOrEqualTo(0);

        tailer.stop();
    }

    @Test
    void perf_500_inserts_throughput_and_latency_based_on_inbox_payloads() {
        int totalRows = 500;
        int batchSize = 25;

        List<Long> latenciesMs = new CopyOnWriteArrayList<>();

        String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");

        var tailer = new Wal2JsonTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                Wal2JsonTailerProperties.defaults(
                        Duration.ofMillis(5),
                        Duration.ofMillis(50),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(100)
                                                 ),
                PgSlotMode.CREATE_IF_MISSING,
                Optional.empty(),
                Optional.empty()
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));

        long startNs = System.nanoTime();

        for (int i = 0; i < totalRows; i += batchSize) {
            int base = i;
            jdbi.useTransaction(h -> {
                for (int j = 0; j < batchSize && base + j < totalRows; j++) {
                    long   ts      = System.currentTimeMillis();
                    String payload = "id=" + (base + j) + ";ts=" + ts;
                    h.execute("insert into " + TEST_TABLE + "(payload) values(?)", payload);
                }
            });
        }

        Awaitility.await()
                  .atMost(Duration.ofSeconds(30))
                  .pollInterval(Duration.ofMillis(100))
                  .untilAsserted(() -> {
                      int inserts = countInsertKindsInInbox(slotName);
                      assertThat(inserts).isGreaterThanOrEqualTo(totalRows);
                  });

        long   endNs      = System.nanoTime();
        double seconds    = (endNs - startNs) / 1_000_000_000.0;
        double throughput = totalRows / seconds;

        // Compute latency by scanning all inbox payload_json for ts=...
        long now = System.currentTimeMillis();
        for (String walJson : listInboxPayloads(slotName)) {
            var ts = TS_IN_PAYLOAD.matcher(walJson);
            while (ts.find()) {
                long sent = Long.parseLong(ts.group(1));
                latenciesMs.add(now - sent);
            }
        }

        var  sorted = latenciesMs.stream().sorted().toList();
        long p50    = percentile(sorted, 0.50);
        long p95    = percentile(sorted, 0.95);
        long p99    = percentile(sorted, 0.99);
        long max    = sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1);

        System.out.println("""
                           Wal2JsonTailer PERF RESULT (from inbox)
                           --------------------------------------
                           rows               = %d
                           totalSeconds       = %.2f
                           throughputRows/sec = %.1f
                           latencyMs:
                             p50 = %d
                             p95 = %d
                             p99 = %d
                             max = %d
                           """.formatted(totalRows, seconds, throughput, p50, p95, p99, max));

        tailer.stop();

        // We should have at least totalRows timestamps extracted if we saw all rows
        assertThat(sorted.size()).isGreaterThanOrEqualTo(totalRows);
    }

    private boolean anyInboxPayloadContains(String slotName, String needle) {
        return listInboxPayloads(slotName).stream().anyMatch(s -> s != null && s.contains(needle));
    }

    private int countInsertKindsInInbox(String slotName) {
        int count = 0;
        for (String walJson : listInboxPayloads(slotName)) {
            var m = INSERT_KIND.matcher(walJson);
            while (m.find()) count++;
        }
        return count;
    }

    private List<String> listInboxPayloads(String slotName) {
        return jdbi.withHandle(h ->
                                       h.createQuery(bind("""
                                                          select payload_json
                                                          from {:tableName}
                                                          where slot_name = :slot
                                                            and status = :status
                                                          order by lsn
                                                          """, arg("tableName", CdcSql.DEFAULT_CDC_TABLE_NAME)))
                                        .bind("slot", slotName)
                                        .bind("status", CdcInboxRepository.InboxStatus.RECEIVED.name())
                                        .mapTo(String.class)
                                        .list()
                              );
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return -1;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

}
