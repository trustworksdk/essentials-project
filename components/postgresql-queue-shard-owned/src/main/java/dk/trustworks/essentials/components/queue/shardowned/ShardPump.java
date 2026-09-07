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

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One thread and one connection, serving many shards.
 * <p>
 * <b>Why this exists.</b> Each owner used to run its own loop on its own thread with its own
 * connection, so database contact scaled with {@code shards x lanes x queues}: nine held connections
 * per queue at four shards, which is 425 at the twenty-five queues and eight shards this was
 * eventually asked about. Nothing about shard ownership required that. A shard's identity is a row in
 * the lease table; its state — cursor, hole map, in-flight set — is a few fields in memory. The read
 * is a primary-key range scan that returns in about a hundred microseconds, so one thread can walk
 * many shards' worth of it. The old implementation's {@code CentralizedMessageFetcher} had already
 * reached the same conclusion with one scheduler thread for every queue.
 * <p>
 * <b>Parking.</b> All the shards a pump serves share one {@link ShardWakeup}, so the listener signals
 * the pump rather than an individual shard — the pump then reads whichever of its shards actually has
 * something. That costs an occasional empty read on a quiet shard and saves a thread per shard.
 * <p>
 * <b>Reconnecting rather than dying</b> is inherited from the owners and matters more here, because a
 * thread that dies now takes several shards down with it rather than one. The lease still bounds the
 * retrying: if the database is genuinely gone the heartbeat cannot renew either, every owner is
 * marked lost, and the loop exits.
 */
public final class ShardPump implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ShardPump.class);

    private final NextGenStorage       storage;
    private final ShardOwnerSettings   settings;
    private final ShardOwnerMetrics    metrics;
    private final ShardWakeup          wakeup = new ShardWakeup();
    private final CopyOnWriteArrayList<LeasedOwner> owners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean        running;
    private final AtomicBoolean        flushOnExit;
    private final String               name;

    public ShardPump(NextGenStorage storage, ShardOwnerSettings settings, ShardOwnerMetrics metrics,
                     AtomicBoolean running, AtomicBoolean flushOnExit, String name) {
        this.storage = requireNonNull(storage, "No storage provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.metrics = requireNonNull(metrics, "No metrics provided");
        this.running = requireNonNull(running, "No running flag provided");
        this.flushOnExit = requireNonNull(flushOnExit, "No flushOnExit flag provided");
        this.name = requireNonNull(name, "No name provided");
    }

    /** The wake-up every shard this pump serves is registered against. */
    public ShardWakeup wakeup() {
        return wakeup;
    }

    /**
     * Hand this pump another shard. Safe while it is running — rebalancing acquires shards long after
     * the pumps have started, and the new owner is picked up on the next iteration.
     */
    public void add(LeasedOwner owner) {
        owners.add(requireNonNull(owner, "No owner provided"));
        // So a pump parked on its backstop starts serving the new shard now rather than up to half a
        // second later.
        wakeup.signal();
    }

    public int shardsServed() {
        return owners.size();
    }

    @Override
    public void run() {
        while (running.get()) {
            try (var connection = storage.connection()) {
                // Reset per connection, not per owner: after a reconnect every owner has to bump
                // attempts again, because the takeover it is recovering from may be its own.
                var takenOver = Collections.newSetFromMap(new IdentityHashMap<LeasedOwner, Boolean>());

                while (running.get()) {
                    var delivered = 0;
                    for (var owner : owners) {
                        // Before the first pump, never after. Pumps start before any shard has been
                        // leased and rebalancing adds shards later, so an owner's first iteration is
                        // routinely not the pump's first — and bumping after it had already delivered
                        // and acknowledged found nothing to bump, which is what a crashed owner's
                        // successor depends on to stop an infinite redelivery loop.
                        if (takenOver.add(owner)) {
                            owner.onTakeover(connection);
                        }
                        if (!owner.leaseHeld()) {
                            continue;
                        }
                        // The wake-up was for one of this pump's shards, not all of them.
                        if (!owner.needsAttention()) {
                            continue;
                        }
                        try {
                            delivered += owner.pumpOnce(connection);
                        } catch (RuntimeException e) {
                            // One thread now serves several shards, so an unchecked exception from
                            // one owner used to end the thread and stall every shard beside it —
                            // silently, because the lease went on being renewed. Whatever is wrong
                            // with this owner is its own problem; the rest keep running.
                            log.error("{}: shard {} threw; continuing with the other shards",
                                      name, owner.shard(), e);
                        }
                    }
                    owners.removeIf(owner -> {
                        if (owner.leaseHeld()) {
                            return false;
                        }
                        takenOver.remove(owner);
                        return true;
                    });

                    if (delivered == 0) {
                        park();
                    }
                }
                if (flushOnExit.get()) {
                    for (var owner : owners) {
                        owner.flushOnStop(connection);
                    }
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (SQLException e) {
                metrics.connectionFailures.increment();
                log.warn("{} lost its connection; reconnecting", name, e);
                try {
                    TimeUnit.MILLISECONDS.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Park until a hint arrives or the backstop fires, shortened by the soonest deadline any of this
     * pump's owners has — a retry falling due on one shard must not be delayed by the others being
     * quiet.
     */
    private void park() throws InterruptedException {
        // The ceiling, not the backstop. `pollBackstop` used to floor every park at 500ms, which put
        // a fixed query rate under a process however quiet it was; the owners' own deadlines already
        // account for their sweeps, retries and flushes, and a notification wakes this pump
        // immediately regardless of how long it intended to sleep.
        var wait = Math.max(settings.pollBackstopMillis(), settings.maxSweepIntervalNanos() / 1_000_000L);
        for (var owner : owners) {
            var deadline = owner.parkDeadlineMillis();
            if (deadline < wait) {
                wait = Math.max(1L, deadline);
            }
        }
        // The honoured/backstop counters are kept by the owners now, in needsAttention, where it is
        // known which shard a wake-up was actually for. Counting them here would count one wake-up
        // per pump regardless of how many shards it served.
        if (!wakeup.await(wait)) {
            metrics.backstopPolls.increment();
        }
    }
}
