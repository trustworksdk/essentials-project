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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.types.LongRange;
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
 * well, and hitting the cap gives up the oldest gap at once and logs a WARN. A new gap is waited for at most
 * {@link #MAX_AWAITED_ORDERS_PER_GAP_END} orders deep from each end; the middle of a wider one is given up at once (see
 * there), so what one gap costs - here, and in the transient gaps the subscription records for it - does not grow with
 * its width.
 * <p>
 * Once {@link #collectTimedOutGaps()} was called, the ranges of the gaps given up after waiting {@code gapTimeout} for
 * them are kept for {@link #drainTimedOutGaps()}, so the subscription can record the give-up with its gap handler
 * ({@code SubscriptionGapHandler#giveUpTransientGaps}) - which makes them permanent gaps for every subscriber of the
 * aggregate type, and may therefore only be told about a gap that was waited for that long. A gap given up because of
 * the cap was not: its transaction may still be in flight. It is dropped here only, and stays a transient gap with the
 * gap handler, so a later subscription waits for it again. Nor is the middle of a wide gap, given up at once: it was not
 * waited for either, and the subscription never recorded it as a transient gap.
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
     * How long a gap is waited for when the subscription's gap handler does not state a threshold: the permanent-gap
     * threshold {@link PostgresqlEventStreamGapHandler} promotes transient gaps after by default
     * ({@code ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120)}). A gap handler that states one
     * ({@code SubscriptionGapHandler#transientGapGiveUpThreshold()}) overrides it - see {@link #startingAfter(String, long, Duration)};
     * with a {@code NoEventStreamGapHandler}, or a promotion strategy that is not a plain age, this is the only rule
     */
    static final Duration DEFAULT_GAP_TIMEOUT      = Duration.ofSeconds(120);
    /**
     * Bound on the runs held above the watermark - each one stands for at least one gap below it - and on the earlier gaps
     */
    static final int      DEFAULT_MAX_TRACKED_GAPS = 10_000;
    /**
     * How many global orders the tracker waits for at <i>each</i> end of a gap an event opens: the lowest ones, right
     * above the highest order delivered before it, and the highest ones, right below the event. The orders between the
     * two ends of a gap wider than twice this are given up at once - treated as delivered, so an event for one of them is
     * dropped - without waiting {@code gapTimeout}, and without being collected for {@link #drainTimedOutGaps()}.
     * <p>
     * Why only the ends: {@code global_event_order} comes from the event table's sequence. {@code nextval} hands out
     * orders in increasing order when a row is inserted and never takes one back, so an order in a gap is either
     * <ul>
     *     <li>held by a transaction still in flight. It took its orders from where the sequence stood at the time, next
     *     to the orders handed out just before and after them, so they lie at one of the two ends: right above the
     *     highest order delivered - taken before the event's, and before whatever moved the sequence forward (a
     *     {@code setval}, a restore) - or right below the event - taken by a concurrent writer after any such move,
     *     before the event's own. Both ends must therefore be waited for; or</li>
     *     <li>never to be committed: burned by a rolled back transaction, or skipped by the sequence moving forward.
     *     Nothing fills it.</li>
     * </ul>
     * Only the first kind can still be delivered, and there are no more of them than the events the in-flight
     * transactions append. Unbounded, a gap of a million orders - a sequence moved a million forward under a running
     * subscription - had a million transient gaps recorded before the event was handed on, and given up order by order
     * once {@code gapTimeout} had passed.
     * <p>
     * Half of {@link #DEFAULT_MAX_TRACKED_GAPS}: one gap is waited for at most as many orders deep as the tracker waits
     * for separate gaps at once - far more than the events normally in flight on one event table. The price: a single
     * transaction holding orders further than this from both ends - an append of more than twice this many events,
     * overtaken by a concurrent commit - has the events in the middle dropped by this subscription when it commits. A gap
     * no wider than twice this is waited for in full.
     */
    static final int      MAX_AWAITED_ORDERS_PER_GAP_END = DEFAULT_MAX_TRACKED_GAPS / 2;

    /**
     * What {@link #markDelivered} found. For {@link Kind#OPENED_GAP} the gap is {@code [gapFromInclusive .. order - 1]},
     * of which {@code givenUpAtOnce} - the middle of a gap wider than twice {@link #MAX_AWAITED_ORDERS_PER_GAP_END} - is
     * not waited for; for {@link Kind#FILLED_GAP} it is the order itself
     */
    record Delivery(Kind kind, long gapFromInclusive, Optional<LongRange> givenUpAtOnce) {
        static final Delivery DUPLICATE = new Delivery(Kind.DUPLICATE, 0);
        static final Delivery IN_ORDER  = new Delivery(Kind.IN_ORDER, 0);

        Delivery(Kind kind, long gapFromInclusive) {
            this(kind, gapFromInclusive, Optional.empty());
        }

        boolean isNew() {
            return kind != Kind.DUPLICATE;
        }

        /**
         * For {@link Kind#OPENED_GAP}: the orders below {@code order} - the event's - that the tracker waits for, lowest
         * first: the whole gap, or the two ends of a wide one. Empty for any other kind
         */
        List<LongRange> awaitedGapsBelow(long order) {
            if (kind != Kind.OPENED_GAP) return List.of();
            return givenUpAtOnce.map(middle -> List.of(LongRange.between(gapFromInclusive, middle.fromInclusive - 1),
                                                       LongRange.between(middle.getToInclusive() + 1, order - 1)))
                                .orElseGet(() -> List.of(LongRange.between(gapFromInclusive, order - 1)));
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
     * The ranges of orders given up on after {@code gapTimeout} since the last {@link #drainTimedOutGaps()} - null while
     * they are not collected
     */
    private List<LongRange>                         timedOut;

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
        // Saturating: a threshold of centuries must not overflow into a negative timeout that gives every gap up at once
        this.gapTimeoutNanos = gapTimeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : gapTimeout.toNanos();
        this.maxTrackedGaps = maxTrackedGaps;
        this.nanoClock = requireNonNull(nanoClock, "No nanoClock provided");
        this.watermark = watermarkInclusive;
        this.highestDelivered = watermarkInclusive;
    }

    /**
     * A tracker with the default gap timeout and cap, aged by {@link System#nanoTime()}
     */
    static CdcDeliveryTracker startingAfter(String name, long watermarkInclusive) {
        return startingAfter(name, watermarkInclusive, DEFAULT_GAP_TIMEOUT);
    }

    /**
     * How long a subscription whose gap handler is this one waits for a gap: what the handler states
     * ({@link SubscriptionGapHandler#transientGapGiveUpThreshold()}), so a customised promotion threshold is followed;
     * {@link #DEFAULT_GAP_TIMEOUT} when it states none - a no-op handler, a promotion strategy that is not a plain age,
     * a handler written before the method existed - or none is in use. The tracker has to give up on a gap by itself
     * in all those cases: nothing else would end its wait
     */
    static Duration gapTimeoutFor(Optional<SubscriptionGapHandler> gapHandler) {
        return gapHandler.flatMap(SubscriptionGapHandler::transientGapGiveUpThreshold)
                         .filter(threshold -> !threshold.isNegative() && !threshold.isZero())
                         .orElse(DEFAULT_GAP_TIMEOUT);
    }

    /**
     * A tracker that waits {@code gapTimeout} for a gap, with the default cap, aged by {@link System#nanoTime()}
     */
    static CdcDeliveryTracker startingAfter(String name, long watermarkInclusive, Duration gapTimeout) {
        return new CdcDeliveryTracker(name, watermarkInclusive, gapTimeout, DEFAULT_MAX_TRACKED_GAPS, System::nanoTime);
    }

    /**
     * Wait for these orders too, although they are at or below the watermark: the transient gaps the subscriber's gap
     * handler still has recorded from before this subscription started. Its resume point may be past them - it advances
     * past a gap, since gap-filled events arrive late - so without them an event that fills one of those gaps after a
     * restart would be dropped. They are waited for {@code gapTimeout} from now; orders above the watermark are ignored,
     * as the subscription delivers those anyway. Called once, before anything is delivered. The lowest ones beyond the cap
     * are dropped without being waited for, so they are never collected for {@link #drainTimedOutGaps()}: they stay
     * transient gaps, and the next subscription waits for them again.
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
     * Keep the orders of the gaps given up on after {@code gapTimeout} from now on, until {@link #drainTimedOutGaps()}
     * takes them - for a subscription that records the give-up with its gap handler. Without it they are not kept at
     * all.
     */
    synchronized void collectTimedOutGaps() {
        if (timedOut == null) {
            timedOut = new ArrayList<>();
        }
    }

    /**
     * Gives up first on the gaps whose {@code gapTimeout} has passed, so a subscription that is stopped while idle
     * drains them too.
     *
     * @return the ranges of orders given up on after waiting {@code gapTimeout} for them - the gaps below the highest
     * order delivered, and the earlier gaps - since the last call, lowest first per kind; none unless
     * {@link #collectTimedOutGaps()} was called. One range per gap, which is at most {@link #MAX_AWAITED_ORDERS_PER_GAP_END}
     * twice over wide. Never a gap given up because of the cap, nor the middle of a wide gap (see the class javadoc)
     */
    synchronized List<LongRange> drainTimedOutGaps() {
        giveUpExpiredGaps();
        if (timedOut == null || timedOut.isEmpty()) {
            return List.of();
        }
        var drained = List.copyOf(timedOut);
        timedOut.clear();
        return drained;
    }

    /**
     * Put back ranges {@link #drainTimedOutGaps()} returned that could not be recorded with the gap handler, so the next
     * drain returns them again
     */
    synchronized void requeueTimedOutGaps(List<LongRange> ranges) {
        requireNonNull(ranges, "No ranges provided");
        if (timedOut != null) {
            timedOut.addAll(0, ranges);
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
        long now     = nanoClock.getAsLong();
        long gapFrom = previousHighest + 1;
        long gapTo   = order - 1;
        if (gapTo - gapFrom + 1 > 2L * MAX_AWAITED_ORDERS_PER_GAP_END) {
            // Held by no transaction that can still commit - see MAX_AWAITED_ORDERS_PER_GAP_END. A run of its own, as if
            // delivered, so its events are dropped; the two ends are separate gaps, each waited for from now
            var middle = LongRange.between(gapFrom + MAX_AWAITED_ORDERS_PER_GAP_END, gapTo - MAX_AWAITED_ORDERS_PER_GAP_END);
            runsAboveWatermark.put(middle.fromInclusive, new Run(middle.getToInclusive(), now));
            runsAboveWatermark.put(order, new Run(order, now));
            log.warn("[{}] Global order {} opened a gap of {} orders above {} - waiting for the {} lowest and the {} highest of them, and giving up the {} in between ({}) at once, " +
                             "as no transaction still in flight can hold them: the global order sequence was moved forward, or a large append rolled back",
                     name, order, gapTo - gapFrom + 1, previousHighest, MAX_AWAITED_ORDERS_PER_GAP_END, MAX_AWAITED_ORDERS_PER_GAP_END,
                     middle.getToInclusive() - middle.fromInclusive + 1, middle);
            return new Delivery(Kind.OPENED_GAP, gapFrom, Optional.of(middle));
        }
        runsAboveWatermark.put(order, new Run(order, now));
        return new Delivery(Kind.OPENED_GAP, gapFrom);
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
            giveUpLowestGap("it was not delivered within " + Duration.ofNanos(gapTimeoutNanos) + " - most likely its transaction rolled back", true);
        }
        if (!earlierGaps.isEmpty() && now - earlierGapsSince >= gapTimeoutNanos) {
            log.debug("[{}] Gave up waiting for {} transient gap(s) recorded before this subscription started", name, earlierGaps.size());
            if (timedOut != null) {
                timedOut.addAll(contiguousRanges(earlierGaps));
            }
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
            // Not waited for gapTimeout: not collected, so the gap handler keeps it a transient gap - see the class javadoc
            giveUpLowestGap("more than " + maxTrackedGaps + " gaps were waited for at once", false);
        }
    }

    /**
     * @param afterTimeout whether the gap was waited for {@code gapTimeout} - only then is it collected for
     *                     {@link #drainTimedOutGaps()}
     */
    private void giveUpLowestGap(String reason, boolean afterTimeout) {
        var lowest = runsAboveWatermark.pollFirstEntry();
        log.debug("[{}] Gave up waiting for global order(s) {}..{} - {}", name, watermark + 1, lowest.getKey() - 1, reason);
        if (afterTimeout && timedOut != null) {
            timedOut.add(LongRange.between(watermark + 1, lowest.getKey() - 1));
        }
        watermark = lowest.getValue().end;
    }

    /**
     * {@code orders} as the fewest ranges that cover exactly them, lowest first
     */
    private static List<LongRange> contiguousRanges(SortedSet<Long> orders) {
        var  ranges = new ArrayList<LongRange>();
        Long from   = null;
        long to     = 0;
        for (long order : orders) {
            if (from != null && order == to + 1) {
                to = order;
                continue;
            }
            if (from != null) {
                ranges.add(LongRange.between(from, to));
            }
            from = order;
            to = order;
        }
        if (from != null) {
            ranges.add(LongRange.between(from, to));
        }
        return ranges;
    }

    @Override
    public synchronized String toString() {
        return "CdcDeliveryTracker{" + name + ", watermark=" + watermark + ", highestDelivered=" + highestDelivered +
                ", runsAboveWatermark=" + runsAboveWatermark.size() + ", earlierGaps=" + earlierGaps.size() + '}';
    }
}
