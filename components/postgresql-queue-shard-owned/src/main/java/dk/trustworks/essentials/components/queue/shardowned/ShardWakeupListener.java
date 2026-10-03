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

import dk.trustworks.essentials.shared.Lifecycle;
import org.postgresql.PGConnection;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Tier 1 wake-up (§4.5): one dedicated connection listening for shard hints.
 * <p>
 * Three rules, each of which the design says correctness or scale depends on:
 * <ul>
 *     <li><b>A dedicated connection.</b> A listener that also does work delays every notification
 *         queued behind whatever it is doing.</li>
 *     <li><b>Hints, not payloads.</b> The notification carries only which shard changed. The owner
 *         then reads normally, so nothing about delivery depends on a notification being received,
 *         being received once, or being received in order.</li>
 *     <li><b>Correctness never depends on this.</b> Owners still park with a backstop timeout and
 *         still run the head sweep. Losing every notification degrades latency to the backstop
 *         interval and nothing else — which is what makes it safe to coalesce aggressively.</li>
 * </ul>
 */
public final class ShardWakeupListener implements Lifecycle, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ShardWakeupListener.class);

    public static final String CHANNEL = "shard_queue_wakeup";

    private final DataSource               dataSource;
    /**
     * Keyed {@code queueId:lane:shard} — the notification payload, so routing needs no other state.
     */
    private final Map<String, ShardWakeup> wakeups;
    private final AtomicBoolean            running               = new AtomicBoolean();
    private final LongAdder                notificationsReceived = new LongAdder();
    private final LongAdder                reconnects            = new LongAdder();
    private       Thread                   thread;

    /**
     * One listener serves every queue and both lanes. The channel was always global and the payload
     * always carried the queue id; constructing one of these per queue simply spent a connection per
     * queue to do what one connection can do for all of them.
     */
    public ShardWakeupListener(DataSource dataSource, Map<String, ShardWakeup> wakeups) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.wakeups = requireNonNull(wakeups, "No wakeups provided");
    }

    /**
     * {@inheritDoc}
     * <p>
     * The thread used to be created and started in the constructor, which was wrong twice over: it
     * published {@code this} to another thread before construction had finished — the listen loop
     * could observe a partially initialised object — and it left the class outside
     * {@link Lifecycle}, which is how every other long-lived resource in this project is started and
     * stopped. Idempotent, per the contract.
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::listen, "shard-queue-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        var listening = thread;
        thread = null;
        if (listening != null) {
            listening.interrupt();
        }
    }

    @Override
    public boolean isStarted() {
        return running.get();
    }

    /**
     * Equivalent to {@link #stop()}.
     */
    @Override
    public void close() {
        stop();
    }

    private void listen() {
        // Reconnect rather than exit.
        //
        // This loop used to end at the first exception, and its failure is silent by construction:
        // owners fall back to the backstop poll, so messages keep arriving and only the latency
        // changes — from sub-millisecond to up to half a second. Nothing fails and nothing alerts,
        // so the tier the design's headline latency depends on would simply be gone for the life of
        // the process, after any connection blip at all.
        //
        // Also note LISTEN is per-connection: a new connection has to re-register, which is the part
        // a naive "just retry the read" would miss.
        while (running.get()) {
            try (var connection = dataSource.getConnection()) {
                try (var statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                reconnects.increment();
                var pgConnection = connection.unwrap(PGConnection.class);
                while (running.get()) {
                    var notifications = pgConnection.getNotifications(200);
                    if (notifications == null) {
                        continue;
                    }
                    for (var notification : notifications) {
                        notificationsReceived.increment();
                        signal(notification.getParameter());
                    }
                }
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.warn("Wake-up listener lost its connection; re-establishing LISTEN", e);
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * How many times LISTEN has been established — one on startup, one per recovery after that.
     */
    public long reconnects() {
        return reconnects.sum();
    }

    /**
     * @param payload {@code queueId:shard}
     */
    private void signal(String payload) {
        if (payload == null) {
            return;
        }
        // The payload IS the key: queueId:lane:shard. The lane is in it because the two lanes have
        // separate owners reading separate tables — without it an unordered enqueue would wake the
        // ordered owner of the same shard into a read that can only return nothing.
        var wakeup = wakeups.get(payload);
        if (wakeup != null) {
            wakeup.signal();
        }
    }

    /**
     * Issue a wake-up hint inside the caller's transaction, so it is delivered only if the enqueue
     * commits — and is discarded with the enqueue if it does not.
     * <p>
     * Issued by the application at transaction end rather than by a row-level trigger. A trigger
     * would turn every message into a notification, and PostgreSQL serializes notification-queue
     * access across the whole cluster; that is the difference between a hint that costs nothing and
     * one that becomes the bottleneck.
     */
    public static String key(short queueId, String lane, int shard) {
        return queueId + ":" + lane + ":" + shard;
    }

    public static void notifyShard(Connection connection, short queueId, String lane, int shard) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_notify(?, ?)")) {
            statement.setString(1, CHANNEL);
            statement.setString(2, key(queueId, lane, shard));
            statement.execute();
        }
    }

    public long notificationsReceived() {
        return notificationsReceived.sum();
    }

}
