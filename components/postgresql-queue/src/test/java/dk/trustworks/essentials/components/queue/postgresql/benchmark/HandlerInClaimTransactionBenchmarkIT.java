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

package dk.trustworks.essentials.components.queue.postgresql.benchmark;

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.jdbi.v3.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 and B4 measured together, as one experiment — because B4 requires the lock to span the handler, which
 * <em>is</em> B2.
 *
 * <h2>The lever, and why this is the last untried idea on it</h2>
 * Everything that has measured as significant in this investigation reduces to transactions per message. Today a
 * delivery costs <b>two</b> commits: one to claim (marking {@code is_being_delivered}) and one to acknowledge.
 * Running the handler inside the claim transaction makes it <b>one</b> — and unlike batched acknowledgement, which
 * gets there by deferring the completion record and therefore cannot serve ordered traffic (§2), this defers
 * nothing. It commits once per message, so a key's successor is released at that commit.
 *
 * <h2>Three arms</h2>
 * <ul>
 *     <li><b>{@code TWO_TRANSACTIONS}</b> — today: claim commits, handler runs outside any transaction, acknowledge
 *     commits.</li>
 *     <li><b>{@code HANDLER_IN_TRANSACTION}</b> (B2) — claim, savepoint, handler, release, delete, one commit. The
 *     savepoint is around the handler <em>only</em>, so a failure rolls back the handler's work without losing the
 *     claim's attempt increment — which is the specific defect that makes {@code FullyTransactional} unusable.</li>
 *     <li><b>{@code ADVISORY_LOCK}</b> (B4 + B2) — as above, but the claim takes
 *     {@code pg_try_advisory_xact_lock} instead of writing {@code is_being_delivered}. No write on claim at all,
 *     so no index churn there, and the lock is released by the commit rather than needing a second statement.
 *     This is the idea B1's failure promoted: same benefit, without either cost that sank B1.</li>
 * </ul>
 *
 * <h2>Why the sweep is handler duration, and why a single ratio would be a lie</h2>
 * Holding the transaction across the handler holds a <b>connection</b> and pins the <b>xmin horizon</b> for the
 * handler's duration — the mechanism behind the 5.7× artefact §7 had to diagnose and discard. So the two arms
 * trade against each other along exactly one axis: fewer commits per message versus a connection held for longer.
 * At a zero-cost handler the one-transaction arms should win outright; at a slow handler they should lose, because
 * throughput becomes bounded by {@code pool size / handler duration} while the baseline releases its connection
 * and can keep far more messages in flight than it has connections.
 * <p>
 * <b>The deliverable is therefore the crossover point</b>, not a verdict. Reporting one number here would repeat
 * the mistake this investigation has already had to correct four times.
 * <p>
 * Opt-in via {@code -Dbenchmark.run=true}; sweep with {@code -Dclaimbench.handlerMs=0,1,5,25}.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class HandlerInClaimTransactionBenchmarkIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("claim-txn-benchmark-db");

    private static final String TABLE       = PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME;
    /**
     * Configurable because the ratio between them is the experiment's second axis, not a detail. With workers
     * &le; pool size the one-transaction arms can never be penalised for holding a connection across the handler -
     * there is always a spare - so the crossover the design risk predicts is unreachable and a sweep would report a
     * flat line and call it a result. Raise workers above the pool to reach it.
     */
    private static final int WORKERS   = Integer.getInteger("claimbench.workers", 10);
    private static final int POOL_SIZE = Integer.getInteger("claimbench.poolSize", 16);

    private HikariDataSource      dataSource;
    private Jdbi                  jdbi;
    /**
     * Transaction boundaries go through the same factory the component uses, rather than raw {@code Jdbi}
     * transactions - the arms differ only in <em>where</em> those boundaries fall, so using anything other than the
     * production transaction machinery would measure the harness instead.
     */
    private JdbiUnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        dataSource.setUsername(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());
        dataSource.setAutoCommit(false);
        dataSource.setMaximumPoolSize(POOL_SIZE);
        jdbi = Jdbi.create(dataSource);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private enum Arm {
        TWO_TRANSACTIONS,
        HANDLER_IN_TRANSACTION,
        ADVISORY_LOCK
    }

    @Test
    void transactions_per_message_versus_handler_duration() {
        var handlerDurations = intsFrom("claimbench.handlerMs", "0,1,5,25");
        var messages         = Integer.getInteger("claimbench.messages", 2000);

        System.out.printf("%n%d messages, %d workers, pool %d%n", messages, WORKERS, POOL_SIZE);
        System.out.printf("%-12s %-16s %-18s %-16s %-10s %-10s%n",
                          "handler ms", "2 txn (ms)", "handler-in-txn", "advisory-lock", "B2 gain", "B4 gain");
        for (var handlerMs : handlerDurations) {
            var baseline = drain(Arm.TWO_TRANSACTIONS, messages, handlerMs);
            var inTxn    = drain(Arm.HANDLER_IN_TRANSACTION, messages, handlerMs);
            var advisory = drain(Arm.ADVISORY_LOCK, messages, handlerMs);
            System.out.printf("%-12d %-16d %-18d %-16d %-10.2f %-10.2f%n",
                              handlerMs, baseline, inTxn, advisory,
                              (double) baseline / inTxn, (double) baseline / advisory);
        }
    }

    private long drain(Arm arm, int messageCount, int handlerMs) {
        seed(messageCount);

        var claimed   = new AtomicInteger();
        var startedAt = System.nanoTime();
        var pool      = Executors.newFixedThreadPool(WORKERS);
        try {
            // Futures, not fire-and-forget: ExecutorService.submit swallows a worker's exception into a Future
            // nobody reads, so a harness that ignores them reports "nothing was drained" instead of the actual
            // error. Every future is checked below.
            var futures = new ArrayList<Future<?>>();
            for (var worker = 0; worker < WORKERS; worker++) {
                futures.add(pool.submit(() -> {
                    while (claimed.get() < messageCount) {
                        var handled = switch (arm) {
                            case TWO_TRANSACTIONS -> twoTransactions(handlerMs);
                            case HANDLER_IN_TRANSACTION -> handlerInTransaction(handlerMs, false);
                            case ADVISORY_LOCK -> handlerInTransaction(handlerMs, true);
                        };
                        if (handled) {
                            claimed.incrementAndGet();
                        } else if (claimed.get() < messageCount) {
                            // Nothing claimable this instant - another worker holds it. Yield rather than spin.
                            Thread.onSpinWait();
                        }
                    }
                }));
            }
            for (var future : futures) {
                try {
                    future.get(5, TimeUnit.MINUTES);
                } catch (ExecutionException e) {
                    throw new IllegalStateException(arm + " worker failed", e.getCause());
                } catch (TimeoutException e) {
                    throw new IllegalStateException(arm + " did not finish within the timeout", e);
                }
            }
            var elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            assertThat(remaining()).as("%s must drain every message", arm).isZero();
            return elapsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Today's shape: the claim commits, the handler runs with no transaction open, the acknowledgement commits.
     * Two commits per message, but the connection is returned to the pool while the handler runs.
     */
    private boolean twoTransactions(int handlerMs) {
        var id = unitOfWorkFactory.withUnitOfWork(uow -> claimByMarking(uow.handle()));
        if (id == null) {
            return false;
        }
        simulateHandler(handlerMs);
        var deleted = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                                 .createUpdate("DELETE FROM " + TABLE + " WHERE id = :id")
                                                                 .bind("id", id)
                                                                 .execute());
        return deleted == 1;
    }

    /**
     * One commit per message, with the handler inside it behind a savepoint.
     *
     * @param useAdvisoryLock B4: take {@code pg_try_advisory_xact_lock} instead of writing {@code is_being_delivered},
     *                        so the claim performs no write at all
     */
    private boolean handlerInTransaction(int handlerMs, boolean useAdvisoryLock) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var handle = uow.handle();
            var id     = useAdvisoryLock ? claimByAdvisoryLock(handle) : claimByMarking(handle);
            if (id == null) {
                return false;
            }
            // Savepoint around the handler ONLY. A handler failure then rolls back the handler's work without
            // discarding the claim - which is precisely what FullyTransactional gets wrong, since rolling back the
            // whole transaction loses the attempt increment and the message is retried forever.
            handle.execute("SAVEPOINT handler");
            try {
                simulateHandler(handlerMs);
                handle.execute("RELEASE SAVEPOINT handler");
            } catch (RuntimeException e) {
                handle.execute("ROLLBACK TO SAVEPOINT handler");
                throw e;
            }
            // Counted only if the DELETE actually removed a row. The advisory arm can win a lock for a row another
            // worker deleted a moment earlier - the lock is on hashtext(id), which says nothing about whether the
            // row still exists - so "claimed" and "drained" are not the same thing there. Counting claims instead
            // would let the arm report completion with rows still in the table.
            var deleted = handle.createUpdate("DELETE FROM " + TABLE + " WHERE id = :id").bind("id", id).execute();
            return deleted == 1;
        });
    }

    private String claimByMarking(Handle handle) {
        return handle.createQuery("""
                                  UPDATE %1$s SET is_being_delivered = TRUE,
                                                  total_attempts     = total_attempts + 1,
                                                  delivery_ts        = now()
                                   WHERE id = (SELECT id FROM %1$s
                                                WHERE queue_name = :queueName
                                                  AND is_dead_letter_message = FALSE
                                                  AND is_being_delivered     = FALSE
                                                ORDER BY next_delivery_ts
                                                  FOR UPDATE SKIP LOCKED
                                                LIMIT 1)
                                  RETURNING id
                                  """.formatted(TABLE))
                     .bind("queueName", "ClaimBench")
                     .mapTo(String.class)
                     .findOne()
                     .orElse(null);
    }

    /**
     * B4's claim: no write, no row marking. The lock is transaction-scoped, so the commit releases it - there is
     * nothing to clean up, and a crashed worker's lock dies with its connection rather than leaving a row marked
     * {@code is_being_delivered} for the stuck-message reset to find.
     * <p>
     * The candidate set is bounded before the lock is tried, because {@code pg_try_advisory_xact_lock} is evaluated
     * per row scanned and locks everything it touches for the transaction's life.
     */
    private String claimByAdvisoryLock(Handle handle) {
        return handle.createQuery("""
                                  SELECT c.id FROM (
                                    SELECT id FROM %1$s
                                     WHERE queue_name = :queueName
                                       AND is_dead_letter_message = FALSE
                                     ORDER BY next_delivery_ts
                                     LIMIT 50
                                  ) c
                                  WHERE pg_try_advisory_xact_lock(hashtext(c.id))
                                  LIMIT 1
                                  """.formatted(TABLE))
                     .bind("queueName", "ClaimBench")
                     .mapTo(String.class)
                     .findOne()
                     .orElse(null);
    }

    /**
     * Stands in for application work. {@code Thread.sleep} rather than busy-work on purpose: the axis under test is
     * how long the transaction stays open, and CPU contention between arms would confound it.
     */
    private static void simulateHandler(int handlerMs) {
        if (handlerMs <= 0) {
            return;
        }
        try {
            Thread.sleep(handlerMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void seed(int messageCount) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + TABLE));
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .build();
        durableQueues.start();
        try {
            var queueName = QueueName.of("ClaimBench");
            var batch     = new ArrayList<Message>();
            for (var i = 0; i < messageCount; i++) {
                batch.add(Message.of("m-" + i));
            }
            durableQueues.queueMessages(queueName, batch);
        } finally {
            durableQueues.stop();
        }
    }

    private long remaining() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT count(*) FROM " + TABLE)
                                                          .mapTo(Long.class)
                                                          .one());
    }

    private static List<Integer> intsFrom(String property, String defaultValue) {
        return Arrays.stream(System.getProperty(property, defaultValue).split(","))
                     .map(String::trim)
                     .filter(value -> !value.isEmpty())
                     .map(Integer::parseInt)
                     .toList();
    }
}
