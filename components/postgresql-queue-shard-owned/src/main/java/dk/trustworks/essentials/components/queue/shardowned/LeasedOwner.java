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

import java.sql.*;

/**
 * A shard owner whose right to act expires and must be renewed.
 * <p>
 * Extracted because the ordered lane had none of this. Its owners took a lease at startup, never
 * renewed it, never checked it, and acknowledged without asserting it — so once the lease elapsed a
 * second node could take the same shard while the first was still delivering, and two owners on one
 * shard is precisely an ordering violation. The lane whose whole purpose is ordering was the one
 * lane the lease mechanism did not protect.
 * <p>
 * The tests did not catch it because they finish well inside the lease lifetime. Nothing about a
 * short test can distinguish "the lease is being renewed" from "the lease has not expired yet".
 */
interface LeasedOwner {

    /**
     * One iteration of the read-dispatch-acknowledge loop, on a connection the caller owns.
     * <p>
     * The loop, the connection and the parking used to live inside each owner, one thread and one
     * connection per shard per lane. Nothing about ownership required that: a shard's identity is a
     * row in the lease table and its state is a few fields in memory, so one thread can walk many
     * shards' worth of a primary-key range scan. Moving the loop out to {@link ShardPump} is what lets
     * database contact be sized independently of shard count.
     *
     * @return how much work was done, so the pump knows whether to park
     */
    int pumpOnce(Connection connection) throws SQLException;

    /**
     * Does this owner have anything to do — a wake-up of its own, work handed to it locally, a retry
     * due, a flush due, or a sweep due?
     * <p>
     * A pump serves many shards and is woken by any of them. Without this it read every shard it
     * owned on every wake-up, which measured 14.5 cursor reads per message. Consuming the shard's own
     * wake-up flag here is what makes the read proportional to the work rather than to the fan-out.
     * The due-by-time cases are what keep correctness independent of notifications: a shard whose
     * signal was lost is still swept on its own schedule.
     */
    boolean needsAttention();

    /** Called once per connection, including after a reconnect. */
    void onTakeover(Connection connection) throws SQLException;

    /** Flush outstanding acknowledgements while the fence is still valid. */
    void flushOnStop(Connection connection) throws SQLException;

    /**
     * Longest this owner may be parked before it needs attention regardless of notifications — a
     * retry falling due. {@link Long#MAX_VALUE} when nothing is pending.
     */
    long parkDeadlineMillis();


    int shard();

    long fence();

    /** {@code "unordered"} or {@code "ordered"} — leases are scoped per lane. */
    String lane();

    boolean leaseHeld();

    /** Called when a renewal is refused, or granted under a fence this owner does not hold. */
    void onLeaseLost();

    /**
     * May this owner dispatch work right now?
     * <p>
     * Distinct from {@link #leaseHeld()}, which answers whether this owner still <em>has</em> its
     * unit. This answers whether its instance has been able to say so recently enough for that belief
     * to be worth anything — see {@code ShardOwnedQueue.deliveryPermitted}. An owner that cannot
     * reach the database keeps its units and its memory, and there is nothing to tell it otherwise;
     * the database has meanwhile been free to hand those units to somebody else for the whole time.
     * <p>
     * Default {@code true}, so an owner with no instance behind it — a test double — is unaffected.
     */
    default boolean deliveryPermitted() {
        return true;
    }
}
