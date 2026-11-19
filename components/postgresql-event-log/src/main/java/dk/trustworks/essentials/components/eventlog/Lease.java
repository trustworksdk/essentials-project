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

import java.util.function.LongSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One exclusive subscription's lease (design {@code docs/archive/event-store-engine-phase7.md} §1.2, §2): written by the
 * {@link LeaseKeeper}, read by the subscription's delivery thread.
 * <ul>
 *     <li>{@link State#STANDBY}: not owned; the keeper tries to claim it every pass.</li>
 *     <li>{@link State#OWNED}: claimed or renewed with {@link #fence()}; {@link #confirmedAtNanos()} is the start of the last
 *     successful claim or renewal on the monotonic clock. The local deadline is {@code confirmedAtNanos + leaseLocalStop}.</li>
 *     <li>{@link State#SUSPENDED}: the delivery thread found the deadline passed and waits; a renewal that returns the lease
 *     makes it {@code OWNED} again, nothing replayed (decision 4).</li>
 *     <li>{@link State#LOST}: a renewal or a fenced write found another fence or owner; the delivery thread leaves the reader
 *     and calls {@link #standby()}.</li>
 * </ul>
 * The deadline is checked by the delivery thread only, so a stuck keeper can never extend it (§2.4).
 */
final class Lease {
    enum State {
        STANDBY,
        OWNED,
        SUSPENDED,
        LOST
    }

    /** A claimed row (§2.2): the fence the database drew and the row's progress */
    record Claim(long fence, long resumeFrom, long resetEpoch, String consistencyMode, String progressModel, String filterDigest) {
        Claim(long fence, long resumeFrom, long resetEpoch, String consistencyMode, String progressModel) {
            this(fence, resumeFrom, resetEpoch, consistencyMode, progressModel, null);
        }
    }

    /** What a first claim inserts when the row does not exist yet (§2.2, {@code LEASE_CLAIM_NEW}) */
    record NewRow(String consistencyMode, String progressModel, long initialResumeFrom, String headTable, String filterDigest) {
        NewRow(String consistencyMode, String progressModel, long initialResumeFrom) {
            this(consistencyMode, progressModel, initialResumeFrom, null, null);
        }
    }

    private final ProgressStore.Key key;
    private final NewRow            newRow;
    private final String            owner;
    private final long              localStopNanos;
    private final LongSupplier      nanoClock;

    private State   state = State.STANDBY;
    private Long    fence;
    private long    confirmedAtNanos;
    /** A claim not yet taken by the delivery thread */
    private Claim   claim;
    /** The row existed when last looked for: the keeper claims with {@code LEASE_CLAIM} only, never inserts */
    private boolean rowSeen;
    private boolean closed;
    private String  lostReason;

    Lease(ProgressStore.Key key, NewRow newRow, String owner, long localStopNanos, LongSupplier nanoClock) {
        this.key = requireNonNull(key, "No key provided");
        this.newRow = requireNonNull(newRow, "No newRow provided");
        this.owner = requireNonNull(owner, "No owner provided");
        this.nanoClock = requireNonNull(nanoClock, "No nanoClock provided");
        if (localStopNanos <= 0) {
            throw new IllegalArgumentException("localStopNanos must be > 0");
        }
        this.localStopNanos = localStopNanos;
    }

    ProgressStore.Key key() {
        return key;
    }

    NewRow newRow() {
        return newRow;
    }

    String owner() {
        return owner;
    }

    synchronized State state() {
        return state;
    }

    /** @return the owned fence (fence and owner), or {@code null} while standby */
    synchronized ProgressStore.Fence fence() {
        return fence == null ? null : new ProgressStore.Fence(fence, owner);
    }

    /** @return the owned fence value, or {@code null} */
    synchronized Long fenceValue() {
        return fence;
    }

    synchronized long confirmedAtNanos() {
        return confirmedAtNanos;
    }

    synchronized boolean rowSeen() {
        return rowSeen;
    }

    synchronized void rowSeen(boolean seen) {
        rowSeen = seen;
    }

    synchronized boolean closed() {
        return closed;
    }

    synchronized String lostReason() {
        return lostReason;
    }

    /** @return whether the keeper should try to claim it: standby, not closed, and no claim waiting to be taken */
    synchronized boolean wantsClaim() {
        return state == State.STANDBY && claim == null && !closed;
    }

    /** @return whether the keeper should renew it */
    synchronized boolean wantsRenewal() {
        return (state == State.OWNED || state == State.SUSPENDED) && !closed;
    }

    // ---- keeper ----

    /**
     * The keeper claimed the row; {@code startNanos} is when the claim statement was sent
     *
     * @return {@code false} if the lease no longer wants it (closed while the claim was in flight): the keeper releases it
     */
    synchronized boolean claimed(Claim claim, long startNanos) {
        requireNonNull(claim, "No claim provided");
        if (closed || state != State.STANDBY) {
            return false;
        }
        this.claim = claim;
        this.fence = claim.fence();
        this.confirmedAtNanos = startNanos;
        this.rowSeen = true;
        this.lostReason = null;
        state = State.OWNED;
        notifyAll();
        return true;
    }

    /**
     * A renewal returned this lease's key with its fence: confirmed at {@code confirmedAt} (the renewal's start, §2.3).
     *
     * @return {@code true} if it was suspended and now resumes
     */
    synchronized boolean renewed(long confirmedAt) {
        if (state != State.OWNED && state != State.SUSPENDED) {
            return false;
        }
        confirmedAtNanos = Math.max(confirmedAtNanos, confirmedAt);
        var resumed = state == State.SUSPENDED && !pastDeadline(nanoClock.getAsLong());
        if (resumed) {
            state = State.OWNED;
        }
        notifyAll();
        return resumed;
    }

    /**
     * Ownership moved (§2.4, §2.6)
     *
     * @return {@code true} if this call made it lost
     */
    synchronized boolean lost(String reason) {
        if (state != State.OWNED && state != State.SUSPENDED) {
            return false;
        }
        lostReason = reason;
        if (claim != null) {
            // Never taken by the delivery thread: nothing to leave, straight back to standby
            claim = null;
            fence = null;
            state = State.STANDBY;
        } else {
            state = State.LOST;
        }
        notifyAll();
        return true;
    }

    // ---- delivery thread ----

    /**
     * Wait until the keeper claims the lease, or {@link #close()}
     *
     * @return the claim, or {@code null} if closed
     */
    synchronized Claim awaitClaim() throws InterruptedException {
        while (claim == null && !closed) {
            wait();
        }
        if (closed) {
            return null;
        }
        var taken = claim;
        claim = null;
        return taken;
    }

    /** @return whether the local deadline passed: owned, and {@code now - confirmedAtNanos > leaseLocalStop} */
    synchronized boolean pastDeadline() {
        return (state == State.OWNED || state == State.SUSPENDED) && pastDeadline(nanoClock.getAsLong());
    }

    private boolean pastDeadline(long now) {
        return now - confirmedAtNanos > localStopNanos;
    }

    /** @return how far past the local deadline {@code now} is, or zero or less if it is not */
    synchronized long nanosPastDeadline() {
        return nanoClock.getAsLong() - confirmedAtNanos - localStopNanos;
    }

    /**
     * Past the deadline: {@code OWNED} becomes {@code SUSPENDED}
     *
     * @return {@code true} if this call suspended it
     */
    synchronized boolean suspendIfPastDeadline() {
        if (state == State.OWNED && pastDeadline(nanoClock.getAsLong())) {
            state = State.SUSPENDED;
            return true;
        }
        return false;
    }

    /**
     * Wait while {@code SUSPENDED}, at most {@code maxNanos}
     *
     * @return the state then
     */
    synchronized State awaitNotSuspended(long maxNanos) throws InterruptedException {
        var deadline = System.nanoTime() + maxNanos;
        while (state == State.SUSPENDED && !closed) {
            var left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            wait(Math.max(1, left / 1_000_000));
        }
        return state;
    }

    /** The delivery thread left the reader after a loss: back to standby, so the keeper claims again */
    synchronized void standby() {
        state = State.STANDBY;
        fence = null;
        claim = null;
        notifyAll();
    }

    /** The subscription stops: no further claim is taken; a waiting delivery thread wakes */
    synchronized void close() {
        closed = true;
        notifyAll();
    }

    @Override
    public synchronized String toString() {
        return "Lease[" + key + ", " + state + ", fence " + fence + "]";
    }
}
