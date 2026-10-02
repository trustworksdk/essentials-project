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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.LongSupplier;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Which global orders one {@link CdcEventStore} subscription has handed downstream - gap-aware, because the CDC bus
 * delivers events in <b>commit</b> order, not in global order.
 * <p>
 * A transaction takes its {@code global_event_order} when it inserts, so a transaction that took a lower order can
 * commit after one that took a higher order. A high-water mark ("deliver only what is above the highest order delivered
 * so far") then drops the lower event when the bus delivers it, and nothing offers it again. The same goes for an event
 * the polling path's gap handler fetches late, and for one that committed below the head a back-fill read.
 * <p>
 * The tracker therefore keeps:
 * <ul>
 *     <li>a contiguous <b>watermark</b> W: every order at or below it was delivered or is known not to be coming;</li>
 *     <li>the runs of orders delivered <b>above</b> W. Every order between W and the highest order delivered that is not
 *     in a run is a <b>gap</b>: an order a transaction holds without having committed it yet, or one a rolled-back
 *     transaction burned and that never commits;</li>
 *     <li><b>earlier gaps</b>: orders at or below the starting point that the subscriber's gap handler still has recorded
 *     as transient gaps from before this subscription started (see {@link #seedEarlierGaps}).</li>
 * </ul>
 * An event passes ({@link #markDelivered}) iff its order is above W and not in a run, or is an earlier gap; delivering
 * it advances W across contiguous runs. A gap is waited for until it is older than {@code gapTimeout} - the oldest
 * first, measured from when a delivered order above it revealed it - and then given up: W moves past it, and an event
 * for it that shows up later is dropped as a duplicate would be. That is the polling path's rule too: its gap handler
 * stops re-querying a transient gap once it promotes it to permanent. The number of runs and of earlier gaps is capped as
 * well, and hitting the cap gives up the oldest gap at once and logs a WARN.
 * <p>
 * Delivering a gap's event after events with higher orders is out of global order. That is the existing contract:
 * the polling path delivers gap-filled events late too, which is why a subscriber's resume point only ever advances
 * ({@code SubscriptionResumePoint.advanceResumeFromAndIncluding}).
 * <p>
 * Thread-safety: every method that reads or changes the runs is {@code synchronized} - one subscription's delivery
 * threads (its {@code Cdc-*} thread, a polling {@code Publish-*} thread, a back-fill thread) may touch it in turn. The
 * bus thread uses only {@link #isAtOrBelowWatermarkAndNotAwaited}, which takes no lock: the shared dispatcher thread
 * must never wait for a subscription.
 */
final class CdcDeliveryTracker {
    private static final Logger log = LoggerFactory.getLogger(CdcDeliveryTracker.class);

    /**
     * How long a gap is waited for: the permanent-gap threshold {@link PostgresqlEventStreamGapHandler} promotes transient
     * gaps after by default ({@code ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120)}). Its
     * promotion strategy is opaque, so a customised one is not reflected here; with a {@code NoEventStreamGapHandler}
     * this is the only rule
     */
    static final Duration DEFAULT_GAP_TIMEOUT      = Duration.ofSeconds(120);
    /**
     * Bound on the runs held above the watermark - each one stands for at least one gap below it - and on the earlier gaps
     */
    static final int      DEFAULT_MAX_TRACKED_GAPS = 10_000;

    /**
     * What {@link #markDelivered} found. For {@link Kind#OPENED_GAP} the gap is {@code [gapFromInclusive .. order - 1]};
     * for {@link Kind#FILLED_GAP} it is the order itself
     */
    record Delivery(Kind kind, long gapFromInclusive) {
        static final Delivery DUPLICATE = new Delivery(Kind.DUPLICATE, 0);
        static final Delivery IN_ORDER  = new Delivery(Kind.IN_ORDER, 0);

        boolean isNew() {
            return kind != Kind.DUPLICATE;
        }
    }

    enum Kind {
        /** Delivered before, or given up on: must not be delivered */
        DUPLICATE,
        /** The order right after the highest delivered so far */
        IN_ORDER,
        /** Above the highest delivered so far, with orders between them not delivered: a new gap */
        OPENED_GAP,
        /** Fills a gap - an order below the highest delivered that was not delivered yet */
        FILLED_GAP
    }

    /**
     * A run {@code [start .. end]} of delivered orders above the watermark, and since when the gap right below it has
     * been known (in {@link #nanoClock} time)
     */
    private static final class Run {
        private long       end;
        private final long gapBelowSince;

        private Run(long end, long gapBelowSince) {
            this.end = end;
            this.gapBelowSince = gapBelowSince;
        }
    }

    private final String                            name;
    private final long                              gapTimeoutNanos;
    private final int                               maxTrackedGaps;
    private final LongSupplier                      nanoClock;
    private final TreeMap<Long, Run>                runsAboveWatermark = new TreeMap<>();
    /** Read lock-free by the bus thread, see {@link #isAtOrBelowWatermarkAndNotAwaited} */
    private final ConcurrentSkipListSet<Long>       earlierGaps        = new ConcurrentSkipListSet<>();
    private volatile long                           watermark;
    private long                                    highestDelivered;
    /** Since when (in {@link #nanoClock} time) the {@link #earlierGaps} have been waited for */
    private long                                    earlierGapsSince;
    private boolean                                 capReachedLogged;

    /**
     * @param name                    used in log statements, e.g. {@code subscriber-aggregateType}
     * @param watermarkInclusive      the highest order the subscription does not want: it starts after it
     * @param gapTimeout              how long a gap is waited for before it is given up
     * @param maxTrackedGaps          bound on the runs above the watermark and on the earlier gaps
     * @param nanoClock               the clock gaps are aged by - {@link System#nanoTime()} outside tests
     */
    CdcDeliveryTracker(String name, long watermarkInclusive, Duration gapTimeout, int maxTrackedGaps, LongSupplier nanoClock) {
        this.name = requireNonNull(name, "No name provided");
        requireNonNull(gapTimeout, "No gapTimeout provided");
        requireTrue(!gapTimeout.isNegative() && !gapTimeout.isZero(), "gapTimeout must be positive");
        requireTrue(maxTrackedGaps > 0, "maxTrackedGaps must be > 0");
        this.gapTimeoutNanos = gapTimeout.toNanos();
        this.maxTrackedGaps = maxTrackedGaps;
        this.nanoClock = requireNonNull(nanoClock, "No nanoClock provided");
        this.watermark = watermarkInclusive;
        this.highestDelivered = watermarkInclusive;
    }

    /**
     * A tracker with the default gap timeout and cap, aged by {@link System#nanoTime()}
     */
    static CdcDeliveryTracker startingAfter(String name, long watermarkInclusive) {
        return new CdcDeliveryTracker(name, watermarkInclusive, DEFAULT_GAP_TIMEOUT, DEFAULT_MAX_TRACKED_GAPS, System::nanoTime);
    }

    /**
     * Wait for these orders too, although they are at or below the watermark: the transient gaps the subscriber's gap
     * handler still has recorded from before this subscription started. Its resume point may be past them - it advances
     * past a gap, since gap-filled events arrive late - so without them an event that fills one of those gaps after a
     * restart would be dropped. They are waited for {@code gapTimeout} from now; orders above the watermark are ignored,
     * as the subscription delivers those anyway. Called once, before anything is delivered.
     */
    synchronized void seedEarlierGaps(Collection<GlobalEventOrder> transientGaps) {
        requireNonNull(transientGaps, "No transientGaps provided");
        earlierGapsSince = nanoClock.getAsLong();
        transientGaps.stream()
                     .mapToLong(GlobalEventOrder::longValue)
                     .filter(order -> order <= watermark)
                     .forEach(earlierGaps::add);
        int skipped = 0;
        while (earlierGaps.size() > maxTrackedGaps) {
            earlierGaps.pollFirst();
            skipped++;
        }
        if (skipped > 0) {
            log.warn("[{}] {} transient gaps were recorded before this subscription started - waiting only for the {} highest of them, the {} lowest are given up",
                     name, earlierGaps.size() + skipped, maxTrackedGaps, skipped);
        }
    }

    /**
     * Record that the event with this order is handed downstream - unless it was delivered before, or given up on.
     *
     * @return whether it may be delivered ({@link Delivery#isNew()}), and whether it opened or filled a gap
     */
    synchronized Delivery markDelivered(long order) {
        giveUpExpiredGaps();
        Delivery delivery;
        if (order <= watermark) {
            delivery = earlierGaps.remove(order) ? new Delivery(Kind.FILLED_GAP, order) : Delivery.DUPLICATE;
        } else if (order > highestDelivered) {
            delivery = deliverAboveHighest(order);
        } else {
            delivery = fillGap(order);
        }
        enforceCap();
        return delivery;
    }

    private Delivery deliverAboveHighest(long order) {
        long previousHighest = highestDelivered;
        highestDelivered = order;
        if (order == previousHighest + 1) {
            if (runsAboveWatermark.isEmpty()) {
                watermark = order;
            } else {
                runsAboveWatermark.lastEntry().getValue().end = order;
            }
            return Delivery.IN_ORDER;
        }
        runsAboveWatermark.put(order, new Run(order, nanoClock.getAsLong()));
        return new Delivery(Kind.OPENED_GAP, previousHighest + 1);
    }

    /**
     * {@code watermark < order <= highestDelivered}: either in a run (a duplicate) or in a gap, which always has a run
     * above it
     */
    private Delivery fillGap(long order) {
        var below = runsAboveWatermark.floorEntry(order);
        if (below != null && below.getValue().end >= order) {
            return Delivery.DUPLICATE;
        }
        var above           = runsAboveWatermark.higherEntry(order);
        var adjacentToBelow = below == null ? order == watermark + 1 : below.getValue().end == order - 1;
        var adjacentToAbove = above.getKey() == order + 1;
        if (below == null && adjacentToBelow) {
            // The lowest gap shrinks from below; the watermark moves up, across the run above if this closed the gap
            watermark = order;
            if (adjacentToAbove) {
                runsAboveWatermark.remove(above.getKey());
                watermark = above.getValue().end;
            }
        } else if (adjacentToBelow) {
            below.getValue().end = adjacentToAbove ? runsAboveWatermark.remove(above.getKey()).end : order;
        } else if (adjacentToAbove) {
            runsAboveWatermark.remove(above.getKey());
            runsAboveWatermark.put(order, above.getValue());
        } else {
            // Splits the gap in two, both known since the run above revealed it
            runsAboveWatermark.put(order, new Run(order, above.getValue().gapBelowSince));
        }
        return new Delivery(Kind.FILLED_GAP, order);
    }

    /**
     * Whether the event with this order was delivered before or given up on - without recording anything
     */
    synchronized boolean isDelivered(long order) {
        giveUpExpiredGaps();
        if (order <= watermark) {
            return !earlierGaps.contains(order);
        }
        if (order > highestDelivered) {
            return false;
        }
        var below = runsAboveWatermark.floorEntry(order);
        return below != null && below.getValue().end >= order;
    }

    /**
     * Lock-free, so the shared bus thread never waits for a subscription: true only for an order that can never be
     * delivered again. False for an order above the watermark that was delivered - that is the delivery thread's call
     */
    boolean isAtOrBelowWatermarkAndNotAwaited(long order) {
        return order <= watermark && !earlierGaps.contains(order);
    }

    /**
     * Where a source that reads forward from one global order - polling - has to start: right after the watermark, so
     * the gaps are read again; what was delivered above them is dropped by {@link #markDelivered}
     */
    synchronized long resumeFromInclusive() {
        giveUpExpiredGaps();
        return watermark + 1;
    }

    /**
     * Where a catch-up that also asks for {@link #awaitedGaps} by order has to read forward from
     */
    synchronized long highestDeliveredExclusive() {
        giveUpExpiredGaps();
        return highestDelivered + 1;
    }

    /**
     * The orders still waited for - the gaps below the highest order delivered and the earlier gaps - lowest first, at
     * most {@code limit} of them. A catch-up asks for these by order, besides reading forward from
     * {@link #highestDeliveredExclusive()}: an event that filled one while the subscription was off the bus is only in
     * the database
     */
    synchronized List<GlobalEventOrder> awaitedGaps(int limit) {
        giveUpExpiredGaps();
        var awaited = new ArrayList<GlobalEventOrder>();
        for (var earlierGap : earlierGaps) {
            if (awaited.size() >= limit) return awaited;
            awaited.add(GlobalEventOrder.of(earlierGap));
        }
        long gapFrom = watermark + 1;
        for (var run : runsAboveWatermark.entrySet()) {
            for (long order = gapFrom; order < run.getKey(); order++) {
                if (awaited.size() >= limit) return awaited;
                awaited.add(GlobalEventOrder.of(order));
            }
            gapFrom = run.getValue().end + 1;
        }
        return awaited;
    }

    /**
     * @return the contiguous watermark - every order at or below it is delivered or given up (earlier gaps aside)
     */
    long watermark() {
        return watermark;
    }

    private void giveUpExpiredGaps() {
        long now = nanoClock.getAsLong();
        while (!runsAboveWatermark.isEmpty() && now - runsAboveWatermark.firstEntry().getValue().gapBelowSince >= gapTimeoutNanos) {
            giveUpLowestGap("it was not delivered within " + Duration.ofNanos(gapTimeoutNanos) + " - most likely its transaction rolled back");
        }
        if (!earlierGaps.isEmpty() && now - earlierGapsSince >= gapTimeoutNanos) {
            log.debug("[{}] Gave up waiting for {} transient gap(s) recorded before this subscription started", name, earlierGaps.size());
            earlierGaps.clear();
        }
    }

    private void enforceCap() {
        while (runsAboveWatermark.size() > maxTrackedGaps) {
            if (!capReachedLogged) {
                capReachedLogged = true;
                log.warn("[{}] Waiting for more than {} gaps in the global order at once - giving up the oldest instead of waiting {} for it. Logged once",
                         name, maxTrackedGaps, Duration.ofNanos(gapTimeoutNanos));
            }
            giveUpLowestGap("more than " + maxTrackedGaps + " gaps were waited for at once");
        }
    }

    private void giveUpLowestGap(String reason) {
        var lowest = runsAboveWatermark.pollFirstEntry();
        log.debug("[{}] Gave up waiting for global order(s) {}..{} - {}", name, watermark + 1, lowest.getKey() - 1, reason);
        watermark = lowest.getValue().end;
    }

    @Override
    public synchronized String toString() {
        return "CdcDeliveryTracker{" + name + ", watermark=" + watermark + ", highestDelivered=" + highestDelivered +
                ", runsAboveWatermark=" + runsAboveWatermark.size() + ", earlierGaps=" + earlierGaps.size() + '}';
    }
}
