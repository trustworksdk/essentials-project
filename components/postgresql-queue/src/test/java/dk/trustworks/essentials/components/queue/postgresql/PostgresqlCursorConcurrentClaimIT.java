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

package dk.trustworks.essentials.components.queue.postgresql;

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ordered cursor claim under <b>concurrent claimers</b> — the case every other cursor test structurally cannot
 * reach.
 *
 * <h2>What this covers that nothing else does</h2>
 * Both cursor gates drive the queue through {@link CentralizedMessageFetcher}, which has a single poll thread and
 * therefore never issues two claims at once. That includes the 2 000-message / 20-parallel-consumer ordering suite:
 * its 20 consumers contend on <em>handling</em>, not on claiming. So nothing established that the cursor claim is
 * correct when two claims run simultaneously — which is what the traditional per-consumer fetcher and any second
 * instance produce, and what this test produces directly.
 *
 * <h2>What it does not establish, having been checked</h2>
 * It is <b>not</b> a deadlock regression test, and the record needs to be straight about that. Run-claiming
 * (measurements §19) deadlocked, and the natural inference was that the shipped head-only claim shares the fault,
 * since {@code UPDATE … FROM candidate} waits on rows another claimer holds rather than skipping them. <b>That
 * inference does not survive testing.</b> Two attempts to reproduce it here — 12 keys / 8 claimers, then 40 keys /
 * 16 claimers with a claim limit of 20 — both passed against the unmodified statement.
 * <p>
 * The reason is that a cycle needs two claimers to lock the same rows in <em>different</em> orders. This statement
 * takes at most one row per key and both claimers scan the key-state table in the same order, so their lock orders
 * agree and no cycle can form. The run claim broke that by taking many rows per key, whose per-key sets interleave.
 * <p>
 * A speculative {@code FOR UPDATE SKIP LOCKED} stage was written and then reverted, because a fix for a defect that
 * cannot be reproduced is a change with only downside. The concurrency coverage is worth keeping regardless: it
 * pins per-key ordering and complete drainage under genuinely concurrent claimers, neither of which was tested
 * before.
 */
@Testcontainers
class PostgresqlCursorConcurrentClaimIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("cursor-concurrent-claim-db");

    private static final String TABLE   = PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME;
    private static final int KEYS       = Integer.getInteger("cursorclaim.keys", 12);
    private static final int PER_KEY    = Integer.getInteger("cursorclaim.perKey", 15);
    private static final int CLAIMERS   = Integer.getInteger("cursorclaim.claimers", 4);
    private static final int CLAIM_LIMIT = Integer.getInteger("cursorclaim.limit", 4);

    private HikariDataSource        dataSource;
    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private DurableQueuesSql        sql;
    private PostgresqlDurableQueues durableQueues;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        dataSource.setUsername(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());
        dataSource.setAutoCommit(false);
        // Deliberately small. The container is shared by the whole module and Failsafe runs suites in parallel
        // forks, so a 16-connection pool plus 8 claimer threads here passes in isolation and times out inside a
        // full build - which is what happened. Sized to be a good citizen; raise it via the system properties when
        // running this class on its own to probe harder.
        dataSource.setMaximumPoolSize(Integer.getInteger("cursorclaim.poolSize", 6));
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
        sql = new DurableQueuesSql(TABLE);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + TABLE + "_key_cursor");
            uow.handle().execute("DROP TABLE IF EXISTS " + TABLE);
        });
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setUseOrderedMessageCursor(true)
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Concurrent threads claiming and acknowledging against overlapping key sets. Asserts three things:
     * <ul>
     *     <li><b>No claimer fails.</b> A deadlock or serialization failure would surface here as an
     *     {@code ExecutionException}. None has been observed - see the class note.</li>
     *     <li><b>The queue drains completely.</b> A claim that marks rows in flight but is never acknowledged
     *     blocks its key permanently, so a stall shows up here as leftover rows rather than as an error.</li>
     *     <li><b>Every key was delivered in {@code key_order}.</b> The point of the whole design, and the thing a
     *     careless deadlock fix would break: {@code SKIP LOCKED} applied to a multi-row run hands out orders 5 and
     *     7 while another claimer holds 6 (§19 defect 2).</li>
     * </ul>
     */
    @Test
    void concurrent_claimers_preserve_per_key_ordering_and_drain_the_queue() throws Exception {
        var queueName = QueueName.of("ConcurrentCursor");
        for (var order = 0; order < PER_KEY; order++) {
            var batch = new ArrayList<Message>();
            for (var key = 0; key < KEYS; key++) {
                batch.add(OrderedMessage.of("m-" + key + "-" + order, "key-" + key, order));
            }
            durableQueues.queueMessages(queueName, batch);
        }
        var total = KEYS * PER_KEY;

        var drained    = new AtomicInteger();
        var lastSeen   = new ConcurrentHashMap<String, Integer>();
        var violations = new ConcurrentLinkedQueue<String>();
        var pool       = Executors.newFixedThreadPool(CLAIMERS);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var claimer = 0; claimer < CLAIMERS; claimer++) {
                futures.add(pool.submit(() -> {
                    while (drained.get() < total) {
                        var claimedMessages = claim(queueName);
                        if (claimedMessages.isEmpty()) {
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            continue;
                        }
                        claimedMessages.forEach(message -> {
                            // Recorded per key. The claim admits at most one message per key at a time, so a
                            // regression to a lower or equal order for a key is an ordering violation.
                            lastSeen.merge(message.key(), message.order(), (previous, current) -> {
                                if (current <= previous) {
                                    violations.add(message.key() + ": saw " + current + " after " + previous);
                                }
                                return current;
                            });
                        });
                        // Counted by what the acknowledgement deleted, not by what was claimed - counting claims
                        // lets this loop finish with rows still in the table.
                        drained.addAndGet(acknowledge(claimedMessages));
                    }
                }));
            }
            for (var future : futures) {
                // The deadlock surfaces here, as an ExecutionException wrapping PSQLException.
                future.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(violations).as("per-key ordering must hold under concurrent claimers").isEmpty();
        assertThat(rowsRemaining()).as("the queue must drain completely").isZero();
        assertThat(drained.get()).isEqualTo(total);
    }

    private List<ClaimedOrderedMessage> claim(QueueName queueName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery(sql.getClaimOrderedViaCursorSql())
                                                          .bind("queueName", queueName)
                                                          .bind("now", Instant.now())
                                                          .bind("limit", CLAIM_LIMIT)
                                                          .map((rs, ctx) -> new ClaimedOrderedMessage(rs.getString("id"),
                                                                                                      rs.getString("key"),
                                                                                                      rs.getInt("key_order")))
                                                          .list());
    }

    private int acknowledge(List<ClaimedOrderedMessage> messages) {
        var ids = messages.stream().map(ClaimedOrderedMessage::id).toList();
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery(sql.getAcknowledgeMessagesViaCursorSql())
                                                          .bindList("ids", ids)
                                                          .mapTo(Integer.class)
                                                          .one());
    }

    private long rowsRemaining() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT count(*) FROM " + TABLE)
                                                          .mapTo(Long.class)
                                                          .one());
    }

    private record ClaimedOrderedMessage(String id, String key, int order) {
    }
}
