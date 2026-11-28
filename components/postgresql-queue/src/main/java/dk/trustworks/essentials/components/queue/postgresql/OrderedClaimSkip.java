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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per queue: whether a poll may leave out the ordered claim, because the queue holds no ordered (keyed) message that
 * could become deliverable.
 * <p>
 * Every poll of the classic engine runs the ordered claim before the unordered one. On a queue that never receives an
 * ordered message it always returns no rows, and costs one round trip on the poller's single thread. Once a check
 * finds no keyed message outside the dead letter queue, polls skip the ordered claim until one of:
 * <ul>
 *     <li>this node commits a transaction that queues, retries or resurrects an ordered message on the queue
 *     ({@link #orderedMessageMayHaveArrived()}) - the next poll claims it without delay;</li>
 *     <li>the re-check interval passes - the next poll checks the table again. That bounds how long an ordered
 *     message queued or resurrected by <em>another</em> node, or by plain SQL, waits: the table-change notification
 *     carries no key, so nothing else tells this node about it.</li>
 * </ul>
 * Keyed messages being delivered or waiting for a delayed retry count as present, so a skip can never begin while one
 * of them is in flight. A dead-letter message does not count: only a resurrection makes it deliverable again.
 * <p>
 * A check races with a concurrent local commit: the check's statement may run before an ordered message commits, and
 * the commit's {@link #orderedMessageMayHaveArrived()} may run before or after the check is recorded. The epoch closes
 * it: the caller reads {@link #epoch()} before the check statement takes its snapshot, a skip is only granted for that
 * epoch, and {@link #shouldSkip(long)} refuses it once the epoch has moved on.
 */
final class OrderedClaimSkip {
    private final AtomicLong epoch = new AtomicLong();
    /**
     * The epoch a skip was granted for; skipping only while {@link #epoch} still equals it
     */
    private volatile long    skipEpoch;
    /**
     * {@code System.nanoTime()} until which the ordered claim is skipped; the skip is off when {@link #skipping} is false
     */
    private volatile long    skipUntilNanos;
    private volatile boolean skipping;
    private volatile long    lastCheckNanos;
    private volatile boolean checkedOnce;

    /**
     * A transaction that queued, retried or resurrected an ordered message on the queue has committed
     */
    void orderedMessageMayHaveArrived() {
        epoch.incrementAndGet();
        skipping = false;
    }

    /**
     * Read before the check statement - see the class javadoc
     */
    long epoch() {
        return epoch.get();
    }

    /**
     * @return true when the ordered claim can be left out of this poll
     */
    boolean shouldSkip(long nowNanos) {
        return skipping && epoch.get() == skipEpoch && nowNanos - skipUntilNanos < 0;
    }

    /**
     * @return true when a skip was granted and nothing local has queued an ordered message since - it may have run out
     */
    boolean skipGranted() {
        return skipping && epoch.get() == skipEpoch;
    }

    /**
     * @return true when a poll should run the existence check: once the previous skip has expired, or, while not
     * skipping, at most once per interval after an ordered claim found nothing
     */
    boolean checkDue(long nowNanos, long intervalNanos) {
        return !checkedOnce || nowNanos - lastCheckNanos >= intervalNanos;
    }

    /**
     * Record an existence check
     *
     * @param epochBeforeCheck {@link #epoch()} read before the check statement ran
     * @param keyedMessagesExist whether the check found a keyed message outside the dead letter queue
     */
    void checked(long epochBeforeCheck, boolean keyedMessagesExist, long nowNanos, long intervalNanos) {
        lastCheckNanos = nowNanos;
        checkedOnce = true;
        if (keyedMessagesExist || epoch.get() != epochBeforeCheck) {
            skipping = false;
            return;
        }
        skipEpoch = epochBeforeCheck;
        skipUntilNanos = nowNanos + intervalNanos;
        skipping = true;
    }

    /**
     * An ordered claim returned messages - stop skipping
     */
    void orderedMessagesClaimed() {
        skipping = false;
    }
}
