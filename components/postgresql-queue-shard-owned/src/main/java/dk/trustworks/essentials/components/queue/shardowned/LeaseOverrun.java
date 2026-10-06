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

import java.util.concurrent.TimeUnit;

/**
 * Notices a handler that ran longer than {@code leaseTtl}, which is the configuration under which a
 * key can end up in two handlers at once.
 * <p>
 * An instance whose heartbeat stalls past {@code leaseTtl} is treated as dead by the rest of the
 * cluster, and its units are taken under a new fence. It stops starting new work before that point,
 * but a handler that is already running cannot be recalled: it runs to completion while the
 * successor starts the same key. Correctness of the queue survives - the old owner's acknowledgement
 * is refused - but the ordered lane's "never in two handlers at once" does not, and neither does the
 * assumption most unordered handlers make that they are not racing a second copy of themselves.
 * <p>
 * That only happens to handlers that outlast what remains of the lease when liveness is lost, so the
 * handler's duration against {@code leaseTtl} is the thing to watch. {@code ShardOwnedOrderedRebalanceIT}
 * found it with a one-second lease and three-second handlers: a one-second heartbeat stall under a
 * loaded build was enough.
 * <p>
 * Counted every time and logged at most once a minute per consumer: a handler that is slow is usually
 * slow every time, and a WARN per message would bury the one line that matters.
 */
final class LeaseOverrun {
    private static final Logger log             = LoggerFactory.getLogger(LeaseOverrun.class);
    private static final long   WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);

    private LeaseOverrun() {
    }

    static void check(ShardOwnerMetrics metrics, ShardOwnerSettings settings, String lane, int shard,
                      long startedNanos) {
        var now       = System.nanoTime();
        var elapsed   = now - startedNanos;
        var leaseNanos = TimeUnit.MILLISECONDS.toNanos(settings.leaseTtlMillis());
        if (elapsed <= leaseNanos) {
            return;
        }
        metrics.handlersOutlastingLease.increment();
        var next = metrics.nextLeaseOverrunWarningNanos.get();
        if (now - next >= 0 && metrics.nextLeaseOverrunWarningNanos.compareAndSet(next, now + WARN_EVERY_NANOS)) {
            log.warn("A handler on {} unit {} ran {} ms, longer than the {} ms leaseTtl. If this instance loses "
                             + "its liveness while a handler like it runs, the rest of the cluster takes the unit and "
                             + "starts the same work alongside it - for the ordered lane, the same key in two handlers "
                             + "at once. Raise leaseTtl well above the slowest handler, or make the handler shorter. "
                             + "{} handler(s) have outlasted the lease so far; this is logged at most once a minute",
                     lane, shard, TimeUnit.NANOSECONDS.toMillis(elapsed), settings.leaseTtlMillis(),
                     metrics.handlersOutlastingLease.sum());
        }
    }
}
