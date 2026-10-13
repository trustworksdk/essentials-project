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

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import org.slf4j.*;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Picks, for one queue, which ordered-claim statement the next poll uses: {@link DurableQueuesSql#buildOrderedSqlStatement}
 * (the default) or {@link DurableQueuesSql#buildOrderedHeadScanSqlStatement} (the head scan).
 * <p>
 * The two statements claim the same messages and are each cheap where the other is expensive: the default walks
 * candidates until it has {@code limit} heads, so it is slow when the queue has fewer heads than that - few keys with
 * many messages each; the head scan visits every key once, so it is slow when the queue has many keys.
 * <p>
 * A poll's elapsed time covers more than the statement's plan - the database round trip and mapping the claimed rows
 * too - so being slow does not by itself mean the other statement would be faster. A queue on a database 15 ms away
 * has every poll slow, and switching on each slow poll would alternate the two statements for ever, running an
 * O(keys) head scan every other poll. So:
 * <ul>
 *     <li>A switch takes {@link #SLOW_POLLS_BEFORE_SWITCH} slow polls in a row - a GC pause or one slow round trip is not
 *     a reason to switch. For the default statement a poll only counts as slow when its claim came back short: a full
 *     claim means there were heads enough.</li>
 *     <li>The last elapsed time of each statement is kept, and the queue switches only when the other statement's last
 *     poll was faster than this one, or has never been measured.</li>
 *     <li>The first poll after a switch is a probe: if it is slow and no faster than the poll that caused the switch,
 *     the queue switches straight back.</li>
 *     <li>A measurement goes stale when the queue changes shape, so a statement that keeps being slow probes the other
 *     one anyway every so many slow polls - {@link #INITIAL_PROBE_INTERVAL}, doubling with each probe that loses, up
 *     to {@link #MAX_PROBE_INTERVAL}. A probe costs one poll.</li>
 * </ul>
 * Thread-safe: traditional consumers of the same queue can record claims concurrently.
 */
final class OrderedClaimStatementChooser {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlDurableQueues.class);

    /**
     * Slow polls in a row it takes to switch to a statement that is faster or not yet measured
     */
    static final int  SLOW_POLLS_BEFORE_SWITCH = 3;
    /**
     * Slow polls in a row after which a statement that is slow probes the other one even though its last measurement
     * was slower
     */
    static final int  INITIAL_PROBE_INTERVAL   = 16;
    static final int  MAX_PROBE_INTERVAL       = 1024;
    private static final long UNMEASURED       = -1;

    private final QueueName queueName;
    private       boolean   byHeadScan;
    private       long      lastDefaultNanos  = UNMEASURED;
    private       long      lastHeadScanNanos = UNMEASURED;
    private       int       slowPolls;
    /**
     * The statement was just switched to, so its next poll decides whether it stays
     */
    private       boolean   probing;
    private       long      probeBaselineNanos;
    private       int       probeInterval     = INITIAL_PROBE_INTERVAL;

    OrderedClaimStatementChooser(QueueName queueName) {
        this.queueName = requireNonNull(queueName, "No queueName provided");
    }

    synchronized boolean byHeadScan() {
        return byHeadScan;
    }

    /**
     * Record how a poll went
     *
     * @param byHeadScan        the statement the poll used
     * @param elapsedNanos      how long the claim took, round trip and row mapping included
     * @param claimed           how many messages it claimed
     * @param limit             how many it asked for
     * @param slowThresholdNanos a poll faster than this never leads to a switch
     */
    synchronized void record(boolean byHeadScan, long elapsedNanos, int claimed, int limit, long slowThresholdNanos) {
        if (byHeadScan != this.byHeadScan) {
            // A concurrent consumer's poll that started before the switch
            return;
        }
        var otherLastNanos = byHeadScan ? lastDefaultNanos : lastHeadScanNanos;
        if (byHeadScan) {
            lastHeadScanNanos = elapsedNanos;
        } else {
            lastDefaultNanos = elapsedNanos;
        }

        if (probing) {
            probing = false;
            slowPolls = 0;
            if (elapsedNanos >= slowThresholdNanos && elapsedNanos >= probeBaselineNanos) {
                probeInterval = Math.min(probeInterval * 2, MAX_PROBE_INTERVAL);
                log.debug("[{}] Ordered claim by the {} took {} ms, no faster than the {} ms that caused the switch - switching back",
                          queueName, statementName(byHeadScan), millis(elapsedNanos), millis(probeBaselineNanos));
                this.byHeadScan = !byHeadScan;
            } else {
                probeInterval = INITIAL_PROBE_INTERVAL;
            }
            return;
        }

        var slow = elapsedNanos >= slowThresholdNanos && (byHeadScan || claimed < limit);
        if (!slow) {
            slowPolls = 0;
            return;
        }
        slowPolls++;
        if (otherLastNanos == UNMEASURED || otherLastNanos < elapsedNanos) {
            if (slowPolls >= SLOW_POLLS_BEFORE_SWITCH) {
                log.debug("[{}] Ordered claim by the {} took {} ms for {} of {} messages, {} slow polls in a row - switching to the {}",
                          queueName, statementName(byHeadScan), millis(elapsedNanos), claimed, limit, slowPolls, statementName(!byHeadScan));
                switchFrom(byHeadScan, elapsedNanos);
            }
        } else if (slowPolls >= probeInterval) {
            log.debug("[{}] Ordered claim by the {} took {} ms, {} slow polls in a row - probing the {}, last measured at {} ms",
                      queueName, statementName(byHeadScan), millis(elapsedNanos), slowPolls, statementName(!byHeadScan), millis(otherLastNanos));
            switchFrom(byHeadScan, elapsedNanos);
        }
    }

    private void switchFrom(boolean byHeadScan, long elapsedNanos) {
        this.byHeadScan = !byHeadScan;
        probing = true;
        probeBaselineNanos = elapsedNanos;
        slowPolls = 0;
    }

    private static String statementName(boolean byHeadScan) {
        return byHeadScan ? "head scan" : "default statement";
    }

    private static long millis(long nanos) {
        return Duration.ofNanos(nanos).toMillis();
    }
}
