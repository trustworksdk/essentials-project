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

    /**
     * Called once per connection, including after a reconnect - and, when it throws on a healthy connection,
     * again by the pump {@link ShardPump#TAKEOVER_RETRY_NANOS} after each failure until it completes. {@link #pumpOnce} is not called before it has completed,
     * so it must be safe to call again after failing part way through.
     * <p>
     * <b>Retrying has no bound, and the shard stays leased while it fails.</b> The owner is not ended: the
     * heartbeat keeps renewing its lease, so no other instance takes the shard, and the shard delivers nothing.
     * That is deliberate. A failure caused by data, such as a trigger or constraint refusing the exhaustion
     * dead-letter move, is database-wide: releasing the shard would only hand the same failure to the next
     * instance and move the shard back and forth between instances. A failing {@link #pumpOnce} is also retried
     * with the lease kept; it is counted in {@link ShardOwnerMetrics#pumpFailures} and logged at ERROR at most once a
     * minute per subscription (see {@link PumpFailures}), but it has no current-state count like the one below.
     * Because the shard is leased, {@code QueueHealth.unownedShards()} does not count it. The signal for a takeover is
     * {@link ShardOwnerMetrics#shardsAwaitingTakeover} on this owner's {@link #ownerMetrics()} (readable through
     * {@link ShardOwnedQueue#metrics()}; a {@code PostgresqlMessageQueue} keeps its metrics private, so through
     * {@code MessageQueue} it is the observer callback {@code QueueObserver.takeoverStalled}, published by
     * Micrometer as {@code essentials.queue.shards.awaiting.takeover}), together with
     * {@link ShardOwnerMetrics#takeoverFailures} and an ERROR at most once a minute per subscription, naming one
     * stalled shard and counting the others (see {@link TakeoverStalls}). It clears when the takeover completes or the lease ends.
     */
    void onTakeover(Connection connection) throws SQLException;

    /**
     * Flush outstanding acknowledgements while the fence is still valid.
     */
    void flushOnStop(Connection connection) throws SQLException;

    /**
     * Longest this owner may be parked before it needs attention regardless of notifications — a
     * retry falling due. {@link Long#MAX_VALUE} when nothing is pending.
     */
    long parkDeadlineMillis();


    int shard();

    /**
     * The queue this owner's rows live in. A pump serves several queues, and shard numbers repeat across them.
     */
    short queueId();

    /**
     * This owner's queue-scoped counters, which are NOT the pump's.
     * <p>
     * {@code ShardRuntime} builds its own {@link ShardOwnerMetrics} for the pumps, so anything a pump
     * counts on its own handle is invisible to {@code queue.metrics()} — the same shape of mistake as
     * binding the pump's queue id. Work done on a queue's behalf is counted on that queue.
     */
    ShardOwnerMetrics ownerMetrics();

    long fence();

    /**
     * {@code "unordered"} or {@code "ordered"} — leases are scoped per lane.
     */
    String lane();

    /**
     * Where this owner's lease row stands: the one place that says so. Each owner keeps it in a {@link LeaseRowState}, which
     * owns the transition rule, and every site that counts, drops, re-takes, sheds or releases a unit reads it here (through
     * {@link #leaseHeld()} or {@link #holdsLeaseRow()}).
     * <p>
     * It used to be derived at each site from three objects: the owner's lease flag, whether its pump had died
     * ({@code ShardRuntime.onDeadPump}), and the queue's owner list. An owner whose pump died has ended in memory while its
     * row still names this instance, and a site that asked the lease flag alone dropped such an owner with its row
     * unreleased, re-took its unit, took one free unit more per dead row, or ended it before releasing its row.
     */
    LeaseRow leaseRow();

    /**
     * Whether this owner still serves its unit: {@link LeaseRow#HELD}. Asked by the pump before it pumps the owner, by the
     * fair-share sheds, and by {@code ShardOwnedQueue.shardsHeld()}.
     */
    default boolean leaseHeld() {
        return leaseRow() == LeaseRow.HELD;
    }

    /**
     * Whether this owner's lease row still names this instance: anything but {@link LeaseRow#RELEASED}. Asked by every site
     * that counts the unit towards the fair share, drops the owner, could take the unit again, or checks the row's fence.
     */
    default boolean holdsLeaseRow() {
        return leaseRow() != LeaseRow.RELEASED;
    }

    /**
     * End this owner in memory and keep its lease row: {@link LeaseRow#HELD} to {@link LeaseRow#ENDED_ROW_HELD}. It
     * dispatches nothing more, and its queue still has to release the row. Called by the pump as it dies, and by
     * {@code ShardOwnedQueue.stop()} before it releases the rows. A no-op in any other state.
     */
    void endKeepingLeaseRow();

    /**
     * Stop dispatching anything new, and let what is already in a handler finish. Acknowledgements keep
     * flushing, so everything that completes is retired under this owner's fence. Irreversible, and
     * independent of the ordered lane's rebalancing shed, which gives up and resumes dispatch when its
     * grace runs out; a drain is for an owner that is going away regardless.
     */
    void beginDraining();

    /**
     * What is still in a handler: messages on the unordered lane, keys on the ordered lane.
     */
    int inFlight();

    /**
     * Why an owner's lease ended, which decides how loudly it is reported.
     * <p>
     * The two look identical to the owner — it stops either way — and completely different to whoever
     * reads the log. Before this was distinguished, both said "lease lost under fence N, owner
     * stopping" at WARN, so an ordinary fair-share handover to a second instance produced a warning
     * per unit for something the instance had itself decided to do.
     */
    enum LeaseEnd {
        /**
         * This instance released the row: a fair-share release (an ordered unit once it finished
         * draining), the hand-back of a unit whose pump died, or a stop. Expected, initiated here, and
         * nothing is in doubt.
         */
        RELEASED,
        /**
         * The unit was taken: a renewal refused, or granted under a fence this owner does not hold.
         * Worth a warning — the fence makes it safe, but it means something else believed this
         * instance was gone.
         */
        TAKEN
    }

    /**
     * The state of an owner's lease row, the database's half of its lease. Moves one way only, {@code HELD} to
     * {@code ENDED_ROW_HELD} to {@code RELEASED}, and may skip the middle. The rule is {@link LeaseRowState}'s, the one
     * holder both owners keep the state in; it compares the constants' declaration order, so they are declared in the order
     * they are reached.
     * <p>
     * What each owner does on a transition is its own, keyed on the state {@link LeaseRowState#advanceTo} returns, and the
     * same on both lanes:
     * <ul>
     *     <li>{@link #endKeepingLeaseRow()}, out of {@code HELD}: a DEBUG line, and the owner's wake-up is signalled, so its
     *     pump drops it on its next pass rather than at its backstop. Nothing in any other state.</li>
     *     <li>{@link #onLeaseEnded}, to {@code RELEASED}: a DEBUG line unless it was {@code RELEASED} already, and the wake-up
     *     when it was {@code HELD} (the pump still had the owner in its selection). Called off the pump thread, by the
     *     queue's heartbeat or {@code stop()}.</li>
     *     <li>The owner's own fenced write refused - its acknowledgement flush, or the attempt charge in
     *     {@link #onTakeover}: {@code RELEASED} and a WARN (the charge logs at DEBUG when the owner had already ended, and
     *     counts {@code leasesLost} only when it had not; a refused acknowledgement counts {@code fencedOutAcks}). No
     *     wake-up: those writes run on the owner's own pump, inside a pass, or on {@code stop()}'s flush of owners it had
     *     already ended; the pump drops the owner at the end of that pass, and a wake-up would only cost it one empty
     *     pass more.</li>
     * </ul>
     */
    enum LeaseRow {
        /**
         * The row names this instance under this owner's fence, and the owner serves the unit.
         */
        HELD,
        /**
         * The owner has ended in memory and dispatches nothing more, while the row still names this instance: its pump
         * died ({@link #endKeepingLeaseRow()}), or its queue is stopping. Nothing else can take the unit until the queue
         * releases the row, so the unit still counts towards this instance's fair share. Left for {@code RELEASED} once
         * the queue has released the row, or once the unit is found taken: by the queue's lease check, which calls
         * {@link #onLeaseEnded}, or by one of the owner's own fenced writes being refused - its acknowledgement flush,
         * or the attempt charge in {@link #onTakeover} - which moves to {@code RELEASED} itself and does not go through
         * {@link #onLeaseEnded}.
         */
        ENDED_ROW_HELD,
        /**
         * The row no longer names this owner: this instance released it, or another instance took the unit (found by the
         * queue's lease check, or by the owner's own acknowledgement or takeover attempt charge being fenced out). The
         * owner has ended, and its queue drops it.
         */
        RELEASED
    }

    /**
     * The lease row no longer names this owner: {@link LeaseRow#RELEASED} from either other state, ending the owner if it
     * had not ended. {@link LeaseEnd#RELEASED} is called only once the row has been released, by
     * {@code ShardOwnedQueue.releaseLeaseRows}, the one method that gives a row up; {@link LeaseEnd#TAKEN} when the queue's
     * lease check finds the unit taken. Safe to repeat.
     */
    void onLeaseEnded(LeaseEnd reason);

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
