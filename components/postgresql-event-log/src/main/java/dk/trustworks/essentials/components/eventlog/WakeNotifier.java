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

import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Tier 1's sending half (P0.10's decided shape): one per {@link EventLog}, one virtual thread
 * started on the first {@link #add}, parked while idle. {@link #add} puts a committed aggregate type in a concurrent set;
 * the thread takes the whole set and sends it as one autocommit {@code SELECT pg_notify(…) FROM unnest(?)} on a
 * connection borrowed from the engine's {@code DataSource} and returned at once. What accumulates while a send is in
 * flight goes out in the next one. A failed send is logged at debug and dropped, never retried: the backstop delivers
 * anyway (I7; see docs/design/event-log.md §11).
 * <p>
 * <b>Send coalescing</b>: a send starts no sooner than {@code sendMin} after the previous one started; the
 * types added meanwhile wait in the set and go out together in that send. A {@link #stop} sends what is pending at once.
 * <p>
 * Payload {@code <instance>:<aggregateType>} on channel {@link #CHANNEL}. {@link #kick} sends
 * {@code <instance>:} (no type), which wakes this event log's own {@link WakeListener} out of its blocking read so it
 * notices a stop at once; every listener ignores it.
 */
final class WakeNotifier {
    private static final Logger log = LoggerFactory.getLogger(WakeNotifier.class);

    /** The wake channel: one per database, so a listener needs one {@code LISTEN} */
    static final String CHANNEL = "el_wake";
    static final String SEND    = "SELECT pg_notify(?, p) FROM unnest(?::text[]) p";

    /** Sends one batch of payloads on a channel; {@link #sendOnBorrowedConnection} in normal use, a stub in unit tests */
    interface Sender {
        void send(String channel, List<String> payloads) throws Exception;
    }

    private final String              instance;
    private final Sender              sender;
    private final Supplier<TestSeams> seams;
    private final Set<String>         pending = ConcurrentHashMap.newKeySet();
    private volatile boolean          kicked;
    /** Written under {@code this} */
    private volatile Thread           thread;
    private volatile boolean          stopped;
    private final long                sendMinNanos;
    /** When the last batch send started; notifier thread only */
    private long                      lastSendNanos;
    private boolean                   sentBefore;
    final LongAdder                   sends   = new LongAdder();
    final LongAdder                   failures = new LongAdder();

    // ---- test knobs ----
    /** Sends on this channel instead of {@link #CHANNEL} (the I7 arm "NOTIFY lost on the way") */
    volatile String                   channelForTests = CHANNEL;
    /** {@code false} drops every batch unsent */
    volatile boolean                  enabledForTests = true;

    WakeNotifier(String instance, Sender sender, Supplier<TestSeams> seams) {
        this(instance, sender, seams, java.time.Duration.ZERO);
    }

    /**
     * @param sendMin least time between the starts of two batch sends ({@link EventLogSettings#notifySendMin()}); zero
     *                sends as soon as the previous send returned
     */
    WakeNotifier(String instance, Sender sender, Supplier<TestSeams> seams, java.time.Duration sendMin) {
        this.instance = requireNonNull(instance, "No instance provided");
        this.sender = requireNonNull(sender, "No sender provided");
        this.seams = requireNonNull(seams, "No seams provided");
        this.sendMinNanos = requireNonNull(sendMin, "No sendMin provided").toNanos();
    }

    /** The production sender: one borrowed connection per send, autocommit (committed here if the pool hands out a transaction) */
    static Sender sendOnBorrowedConnection(DataSource dataSource) {
        requireNonNull(dataSource, "No dataSource provided");
        return (channel, payloads) -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(SEND)) {
                statement.setString(1, channel);
                statement.setArray(2, connection.createArrayOf("text", payloads.toArray()));
                statement.execute();
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
        };
    }

    /** {@code <instance>:<aggregateType>} */
    static String payload(String instance, String aggregateType) {
        return instance + ":" + aggregateType;
    }

    /**
     * A transaction that appended to {@code aggregateType} committed: send it with the next batch. Never blocks
     */
    void add(String aggregateType) {
        pending.add(aggregateType);
        signal();
    }

    /** Send {@code <instance>:} on {@link #CHANNEL} with the next batch, whatever the test knobs say */
    void kick() {
        kicked = true;
        signal();
    }

    private void signal() {
        var t = thread;
        if (t == null) {
            synchronized (this) {
                if (stopped) {
                    stopped = false;
                }
                t = thread;
                if (t == null) {
                    t = Thread.ofVirtual().name("eventlog-notifier").start(this::run);
                    thread = t;
                }
            }
        }
        LockSupport.unpark(t);
    }

    /** End the thread once it has sent what is pending; a later {@link #add} starts a new one */
    void stop() {
        Thread t;
        synchronized (this) {
            stopped = true;
            t = thread;
        }
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /** @return whether the thread runs; for tests */
    boolean isRunning() {
        return thread != null;
    }

    private void run() {
        while (true) {
            if (pending.isEmpty() && !kicked) {
                synchronized (this) {
                    if (stopped && pending.isEmpty() && !kicked) {
                        thread = null;
                        return;
                    }
                }
                LockSupport.park(this);
                continue;
            }
            if (kicked) {
                kicked = false;
                send(CHANNEL, List.of(instance + ":"));
            }
            if (sendMinNanos > 0 && sentBefore && !stopped && !pending.isEmpty()) {
                var wait = lastSendNanos + sendMinNanos - System.nanoTime();
                if (wait > 0) {
                    if (seams.get().has(WatermarkMutant.NOTIFY_DROPPED_INSIDE_INTERVAL)) {
                        pending.clear();
                    } else {
                        // Pending types stay in the set and are unioned with what arrives until the interval ends
                        LockSupport.parkNanos(this, wait);
                    }
                    continue;
                }
            }
            // Removing after reading: a concurrent add of a type already taken is covered by this send, which follows it
            var batch = new ArrayList<String>();
            for (var it = pending.iterator(); it.hasNext(); ) {
                batch.add(payload(instance, it.next()));
                it.remove();
            }
            if (batch.isEmpty()) {
                continue;
            }
            try {
                seams.get().hooks().beforeNotifySend(batch);
            } catch (Exception e) {
                log.debug("beforeNotifySend hook failed", e);
            }
            if (enabledForTests) {
                lastSendNanos = System.nanoTime();
                sentBefore = true;
                send(channelForTests, batch);
            }
        }
    }

    private void send(String channel, List<String> payloads) {
        try {
            sender.send(channel, payloads);
            sends.increment();
        } catch (Exception e) {
            failures.increment();
            log.debug("Sending {} wake notification(s) failed; dropped (the backstop covers them)", payloads.size(), e);
            // The notifier owns its thread and ends through stop(), never by interrupt: a restored interrupt flag made
            // every park() in run() return at once, so the idle notifier spun for the rest of the EventLog's life
            Thread.interrupted();
        }
    }

    /** For {@link SQLException}-free unit tests of the coalescing */
    Set<String> pending() {
        return Collections.unmodifiableSet(pending);
    }
}
