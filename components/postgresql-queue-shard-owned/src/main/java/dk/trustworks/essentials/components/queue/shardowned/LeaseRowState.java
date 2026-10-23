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

import dk.trustworks.essentials.components.queue.shardowned.LeasedOwner.LeaseRow;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One owner's {@link LeaseRow}, and the one place its transition rule lives: the state moves one way only, {@code HELD} to
 * {@code ENDED_ROW_HELD} to {@code RELEASED}, may skip the middle, and never moves back.
 * <p>
 * Each owner ({@link ShardOwner}, {@link OrderedShardOwner}) used to keep the state in a field and write each transition by
 * hand, under its {@code stateLock}: eight copies of the rule, four in each owner, plus those in two test doubles. They stay the owners'
 * {@link LeasedOwner#leaseRow()}, and keep their own side effects (the log line, the wake-up, the {@code leasesLost}
 * count), keyed on the state
 * {@link #advanceTo} returns. A compare-and-set is enough, without the owner's lock: no other field of an owner changes
 * together with its lease row.
 * <p>
 * Beside the state, whether a release of the row is in doubt ({@link #markReleaseInDoubt()}): a release that failed may
 * still have gone through on the server, and the owners read a row found no longer naming them by it.
 */
final class LeaseRowState {

    private final AtomicReference<LeaseRow> state = new AtomicReference<>(LeaseRow.HELD);

    /**
     * Set once and never cleared: see {@link #markReleaseInDoubt()}.
     */
    private volatile boolean releaseInDoubt;

    /**
     * Where the lease row stands now.
     */
    LeaseRow get() {
        return state.get();
    }

    /**
     * Move to {@code target} if that is forward of where the row stands, else leave it where it is: asking for
     * {@code ENDED_ROW_HELD} once the row is {@code RELEASED}, or for a state it is already in, changes nothing.
     *
     * @param target the state the caller is moving the row to
     * @return the state the row was in before this call, so the caller can tell whether it moved the row and from where
     */
    LeaseRow advanceTo(LeaseRow target) {
        requireNonNull(target, "No target lease row state provided");
        return state.getAndUpdate(current -> current.ordinal() < target.ordinal() ? target : current);
    }

    /**
     * Record that a release of this row failed ({@code ShardOwnedQueue.releaseLeaseRows} threw) in a way that may have
     * let it go through on the server ({@link #mayHaveGoneThrough}): a statement the driver stopped waiting for - past a
     * network wait bound ({@code NetworkWaitBound}) or a {@code socketTimeout} - can still commit there. Not cleared: no later
     * answer proves that statement will not still commit, and a release is only ever sent for an owner this instance is
     * giving up. Leaves the state where it is.
     */
    void markReleaseInDoubt() {
        releaseInDoubt = true;
    }

    /**
     * Whether a release that failed with {@code failure} may still have gone through on the server: unless the database
     * answered the statement with an error of its own - an {@link SQLException} with an SQL state outside class
     * {@code 08} (connection exception), a trigger refusing the release, say - which rolled the statement back. A
     * connection lost or a read timed out (pgjdbc reports both as {@code 08006}, a network timeout among them), an
     * {@link SQLException} with no SQL state, or any other throwable leaves the outcome unknown. So does a pool that had no
     * connection to give: that statement was never sent, but it cannot be told apart here, and taking it as in doubt only
     * costs counting a later loss of the unit.
     */
    static boolean mayHaveGoneThrough(Throwable failure) {
        if (failure instanceof SQLException sql) {
            var state = sql.getSQLState();
            return state == null || state.startsWith("08");
        }
        return true;
    }

    /**
     * Whether a release of this row failed and may have gone through on the server ({@link #markReleaseInDoubt()}). A row
     * found no longer naming the owner is then read as given up by that release, not as a lease lost.
     */
    boolean releaseInDoubt() {
        return releaseInDoubt;
    }

    @Override
    public String toString() {
        return state.get().name();
    }
}
