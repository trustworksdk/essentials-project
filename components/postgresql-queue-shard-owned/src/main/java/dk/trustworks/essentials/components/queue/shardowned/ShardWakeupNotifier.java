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


package dk.trustworks.essentials.components.queue.shardowned;

import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Sends the wake-up notifications for units no owner in this process serves, after the enqueue that needs them has
 * committed, many to one transaction.
 * <p>
 * A transaction that sends {@code NOTIFY} takes a lock every such transaction in the cluster shares, at commit, and
 * holds it through its WAL flush - so notifying commits run one at a time, each waiting out its own flush. Issued
 * inside every enqueue transaction, that lock was a third of the database's active time at the trading demo's peaks.
 * Here the business transaction never takes it: the keys are queued, and one thread sends whatever accumulated while
 * its previous batch was in flight as a single statement, so the lock is taken once per batch rather than once per
 * enqueue. Nothing waits while the queue is idle - a lone key is sent at once - and batches grow only as fast as
 * enqueues arrive.
 * <p>
 * A notification is a hint (see {@link ShardWakeupListener}), so a batch that fails is dropped and counted rather than
 * retried: the owner's head sweep finds the rows. The same holds for keys still queued when the process dies.
 * <p>
 * The thread starts on the first key and exits after {@link #IDLE_EXIT} with none, so a notifier nobody closes - a
 * producing queue's - holds nothing while unused.
 */
final class ShardWakeupNotifier implements AutoCloseable {
    private static final Logger   log          = LoggerFactory.getLogger(ShardWakeupNotifier.class);
    static final         Duration IDLE_EXIT    = Duration.ofSeconds(30);
    private static final long     WARN_EVERY_MS = 60_000;

    private final DataSource      dataSource;
    private final Object          lock          = new Object();
    private final LongAdder       keysSent      = new LongAdder();
    private final LongAdder       batchesSent   = new LongAdder();
    private final LongAdder       batchFailures = new LongAdder();
    /**
     * Guarded by {@link #lock}
     */
    private       Set<String>     pending       = new LinkedHashSet<>();
    /**
     * Guarded by {@link #lock}
     */
    private       Thread          thread;
    /**
     * Guarded by {@link #lock}
     */
    private       boolean         closed;
    private       long            lastWarnedAt;

    ShardWakeupNotifier(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
    }

    /**
     * Queue wake-up keys ({@link ShardWakeupListener#key}) to be sent. Returns at once.
     */
    void submit(Collection<String> keys) {
        requireNonNull(keys, "No keys provided");
        if (keys.isEmpty()) {
            return;
        }
        synchronized (lock) {
            closed = false;
            pending.addAll(keys);
            if (thread == null) {
                thread = new Thread(this::run, "shard-queue-notifier");
                thread.setDaemon(true);
                thread.start();
            } else {
                lock.notifyAll();
            }
        }
    }

    private void run() {
        while (true) {
            Set<String> batch;
            synchronized (lock) {
                var idleSince = System.nanoTime();
                while (pending.isEmpty()) {
                    var idleFor = System.nanoTime() - idleSince;
                    if (closed || idleFor >= IDLE_EXIT.toNanos()) {
                        thread = null;
                        return;
                    }
                    try {
                        lock.wait(Math.max(1, (IDLE_EXIT.toNanos() - idleFor) / 1_000_000));
                    } catch (InterruptedException e) {
                        thread = null;
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                batch = pending;
                pending = new LinkedHashSet<>();
            }
            send(batch);
        }
    }

    private void send(Set<String> keys) {
        try (var connection = dataSource.getConnection()) {
            var autoCommit = connection.getAutoCommit();
            try (var statement = connection.prepareStatement("SELECT pg_notify(?, key) FROM unnest(?::text[]) AS key")) {
                statement.setString(1, ShardWakeupListener.CHANNEL);
                statement.setArray(2, connection.createArrayOf("text", keys.toArray()));
                statement.execute();
            }
            if (!autoCommit) {
                connection.commit();
            }
            keysSent.add(keys.size());
            batchesSent.increment();
        } catch (SQLException | RuntimeException e) {
            batchFailures.increment();
            var now = System.currentTimeMillis();
            if (now - lastWarnedAt >= WARN_EVERY_MS) {
                lastWarnedAt = now;
                log.warn("Could not send {} shard wake-up notification(s); their owners will find the rows on their next sweep. "
                                 + "Failed batches so far: {}", keys.size(), batchFailures.sum(), e);
            } else {
                log.debug("Could not send {} shard wake-up notification(s)", keys.size(), e);
            }
        }
    }

    long keysSent() {
        return keysSent.sum();
    }

    long batchesSent() {
        return batchesSent.sum();
    }

    long batchFailures() {
        return batchFailures.sum();
    }

    /**
     * Sends what is queued, waiting up to {@code timeout} for it, then lets the thread exit. A later {@link #submit}
     * starts it again.
     */
    void flushAndStop(Duration timeout) {
        Thread running;
        synchronized (lock) {
            closed = true;
            running = thread;
            lock.notifyAll();
        }
        if (running != null) {
            try {
                running.join(timeout.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        flushAndStop(Duration.ofSeconds(2));
    }
}
