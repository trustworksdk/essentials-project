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

import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import org.slf4j.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Keeps the claims of the messages this {@link PostgresqlDurableQueues} instance is handling from being taken back as
 * stuck while their handlers still run.
 * <p>
 * Recovery of a claimed message is by age: the stuck-message reset takes back any claim whose {@code delivery_ts} is
 * older than {@code messageHandlingTimeout}. Without a refresh, a handler that runs longer than that on a live instance
 * gets its message delivered a second time beside it. This class remembers each message the instance has claimed and
 * not yet finished with (acknowledged, retried, dead-lettered, released or deleted), and on every {@link #interval}
 * sets {@code delivery_ts} to now, in one statement, for those that have gone {@link #interval} without a write - so
 * a handler faster than the interval costs no database write at all.
 * <p>
 * The refresh is fenced: a row is only updated while it is still being delivered with the {@code delivery_ts} this
 * instance last wrote, so it never extends a claim another instance has taken since. A message the refresh does not
 * find any more is forgotten. After {@link #maxRefreshDuration} a message is no longer refreshed and a WARN is logged
 * once, so a handler that hangs is still taken back by the reset.
 */
final class ClaimHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(ClaimHeartbeat.class);

    /**
     * Writes the heartbeat: for each claim, set {@code delivery_ts} to {@code now} where the row is still being
     * delivered with the given {@code delivery_ts}
     */
    @FunctionalInterface
    interface Refresher {
        /**
         * @param claims the claims to refresh - id to the {@code delivery_ts} this instance last wrote
         * @param now    the new {@code delivery_ts}, truncated to the database's precision (microseconds)
         * @return the ids that were refreshed; a claim left out is no longer held by this instance
         */
        Set<QueueEntryId> refresh(Map<QueueEntryId, Instant> claims, Instant now);
    }

    private static final class Claim {
        final QueueName queueName;
        final long      claimedAtNanos;
        volatile Instant deliveryTs;
        volatile long    lastWrittenNanos;

        Claim(QueueName queueName, Instant deliveryTs, long claimedAtNanos) {
            this.queueName = queueName;
            this.deliveryTs = deliveryTs;
            this.claimedAtNanos = claimedAtNanos;
            this.lastWrittenNanos = claimedAtNanos;
        }
    }

    private final ConcurrentMap<QueueEntryId, Claim> claims = new ConcurrentHashMap<>();
    private final Duration                           interval;
    private final Duration                           maxRefreshDuration;
    private final Refresher                          refresher;
    private final LongSupplier                       nanoClock;
    private final Supplier<Instant>                  clock;
    private final String                             threadName;
    private       ScheduledExecutorService           executor;

    /**
     * @param interval           how often the heartbeat runs, and how long a claim may go without a write before it is refreshed
     * @param maxRefreshDuration how long after its claim a message stops being refreshed; {@link Duration#ZERO} disables the heartbeat
     * @param refresher          writes the heartbeat
     * @param nanoClock          {@link System#nanoTime()}, or a test clock
     * @param clock              {@link Instant#now()}, or a test clock
     * @param threadName         name of the heartbeat thread
     */
    ClaimHeartbeat(Duration interval,
                   Duration maxRefreshDuration,
                   Refresher refresher,
                   LongSupplier nanoClock,
                   Supplier<Instant> clock,
                   String threadName) {
        this.interval = requireNonNull(interval, "No interval provided");
        requireTrue(interval.toMillis() > 0, "interval must be at least 1 ms");
        this.maxRefreshDuration = requireNonNull(maxRefreshDuration, "No maxRefreshDuration provided");
        requireFalse(maxRefreshDuration.isNegative(), "maxRefreshDuration must not be negative");
        this.refresher = requireNonNull(refresher, "No refresher provided");
        this.nanoClock = requireNonNull(nanoClock, "No nanoClock provided");
        this.clock = requireNonNull(clock, "No clock provided");
        this.threadName = requireNonNull(threadName, "No threadName provided");
    }

    boolean isEnabled() {
        return !maxRefreshDuration.isZero();
    }

    Duration interval() {
        return interval;
    }

    Duration maxRefreshDuration() {
        return maxRefreshDuration;
    }

    /**
     * Remember a message this instance has just claimed. Cheap: one map put, no database access
     */
    void track(QueuedMessage claimed) {
        if (!isEnabled() || claimed == null || !claimed.isBeingDelivered() || claimed.getDeliveryTimestamp() == null) {
            return;
        }
        claims.put(claimed.getId(), new Claim(claimed.getQueueName(), claimed.getDeliveryTimestamp().toInstant(), nanoClock.getAsLong()));
    }

    void track(Optional<QueuedMessage> claimed) {
        claimed.ifPresent(this::track);
    }

    void track(Collection<QueuedMessage> claimed) {
        if (!isEnabled()) {
            return;
        }
        claimed.forEach(this::track);
    }

    /**
     * Forget a message: its delivery ended (acknowledged, retried, dead-lettered, released or deleted)
     */
    void release(QueueEntryId queueEntryId) {
        if (queueEntryId != null) {
            claims.remove(queueEntryId);
        }
    }

    int trackedCount() {
        return claims.size();
    }

    boolean isTracked(QueueEntryId queueEntryId) {
        return claims.containsKey(queueEntryId);
    }

    synchronized void start() {
        if (!isEnabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(ThreadFactoryBuilder.builder()
                                                                                  .nameFormat(threadName + "-%d")
                                                                                  .daemon(true)
                                                                                  .build());
        executor.scheduleWithFixedDelay(this::tickSafely, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.debug("Started the claim heartbeat '{}' with interval {} ms, refreshing a claim for at most {} ms",
                  threadName, interval.toMillis(), maxRefreshDuration.toMillis());
    }

    synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("The claim heartbeat '{}' did not stop within 5 seconds", threadName);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        claims.clear();
    }

    synchronized boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable e) {
            if (IOExceptionUtil.isIOException(e)) {
                log.debug("The claim heartbeat '{}' failed", threadName, e);
            } else {
                log.error("The claim heartbeat '{}' failed", threadName, e);
            }
        }
    }

    /**
     * One heartbeat: refresh, in one statement, every tracked claim that has gone {@link #interval} without a write,
     * and stop tracking the ones past {@link #maxRefreshDuration} and the ones no longer held
     *
     * @return the number of claims refreshed
     */
    int tick() {
        if (claims.isEmpty()) {
            return 0;
        }
        var nowNanos      = nanoClock.getAsLong();
        var intervalNanos = interval.toNanos();
        var maxNanos      = maxRefreshDuration.toNanos();
        var due           = new HashMap<QueueEntryId, Claim>();
        for (var entry : claims.entrySet()) {
            var claim = entry.getValue();
            if (nowNanos - claim.claimedAtNanos >= maxNanos) {
                if (claims.remove(entry.getKey(), claim)) {
                    log.warn("[{}] The handler of message '{}' has run for {} ms, longer than the {} ms its claim is kept alive " +
                                     "(max claim refresh duration). Its claim is no longer refreshed, so once it is older than the " +
                                     "message-handling-timeout the message is taken back as stuck and can be delivered again beside " +
                                     "the running handler. Make the handler shorter or raise the max claim refresh duration",
                             claim.queueName, entry.getKey(), TimeUnit.NANOSECONDS.toMillis(nowNanos - claim.claimedAtNanos),
                             maxRefreshDuration.toMillis());
                }
            } else if (nowNanos - claim.lastWrittenNanos >= intervalNanos) {
                due.put(entry.getKey(), claim);
            }
        }
        if (due.isEmpty()) {
            return 0;
        }

        var now      = clock.get().truncatedTo(ChronoUnit.MICROS);
        var fencedOn = new HashMap<QueueEntryId, Instant>(due.size() * 2);
        due.forEach((id, claim) -> fencedOn.put(id, claim.deliveryTs));
        var refreshed = refresher.refresh(fencedOn, now);
        due.forEach((id, claim) -> {
            if (refreshed.contains(id)) {
                claim.deliveryTs = now;
                claim.lastWrittenNanos = nowNanos;
            } else {
                // Finished meanwhile, or taken back and possibly claimed by another instance: never refresh it again.
                // remove(key, value) keeps a newer claim of the same message by this instance
                claims.remove(id, claim);
            }
        });
        log.trace("[{}] Refreshed {} of {} due claim(s)", threadName, refreshed.size(), due.size());
        return refreshed.size();
    }
}
