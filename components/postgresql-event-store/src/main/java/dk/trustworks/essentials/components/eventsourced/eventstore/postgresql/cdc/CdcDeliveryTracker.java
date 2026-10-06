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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.internal.*;
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
 *     as transient gaps from before this subscription started (see {@link #seedEarlierGaps});</li>
 *     <li><b>earlier middles</b>: the middles of wide gaps, awaited in memory only, that an earlier subscription of the
 *     subscriber in this event store instance still awaited below the starting point (see {@link #seedEarlierMiddles}).</li>
 * </ul>
 * An event passes ({@link #markDelivered}) iff its order is above W and not in a run, or is an earlier gap or in an
 * earlier middle and not delivered by this subscription yet; delivering
 * it advances W across contiguous runs. A gap is waited for until it is older than {@code gapTimeout} - the oldest
 * first, measured from when a delivered order above it revealed it - and then given up: W moves past it, and an event
 * for it that shows up later is dropped as a duplicate would be. That is the polling path's rule too: its gap handler
 * stops re-querying a transient gap once it promotes it to permanent. The number of runs and of earlier gaps is capped as
 * well, and hitting the cap gives up the oldest gap at once and logs a WARN. A gap costs one run whatever its width, and
 * the subscription records at most {@link #MAX_AWAITED_ORDERS_PER_GAP_END} orders deep from each end of it as transient
 * gaps; the middle of a wider one is awaited in memory only (see there).
 * <p>
 * Once {@link #collectTimedOutGaps()} was called, the ranges of the gaps given up after waiting {@code gapTimeout} for
 * them are kept for {@link #drainTimedOutGaps()}, so the subscription can record the give-up with its gap handler
 * ({@code SubscriptionGapHandler#giveUpTransientGaps}) - which makes them permanent gaps for every subscriber of the
 * aggregate type, and may therefore only be told about a gap that was waited for that long. A gap given up because of
 * the cap was not: its transaction may still be in flight. It is dropped here only, and stays a transient gap with the
 * gap handler, so a later subscription waits for it again. The middle of a wide gap, which the subscription never
 * recorded as transient gaps, is drained apart, by {@link #drainTimedOutGapsAwaitedInMemoryOnly()}: there is nothing to
 * record for it.
 * <p>
 * Delivering a gap's event after events with higher orders is out of global order. That is the existing contract:
 * the polling path delivers gap-filled events late too, which is why a subscriber's resume point only ever advances
 * ({@code SubscriptionResumePoint.advanceResumeFromAndIncluding}).
 * <p>
 * Thread-safety: every method that reads or changes the runs is {@code synchronized} - one subscription's delivery
 * threads (its {@code Cdc-*} thread, a polling {@code Publish-*} thread, a back-fill thread) may touch it in turn, and the
 * subscriber reports what it is done with ({@link #doneWith}) on its own. The earlier middles have a monitor of their own,
 * only ever taken inside this one. The bus thread uses only {@link #isAtOrBelowWatermarkAndNotAwaited}, which takes no
 * lock: the shared dispatcher thread must never wait for a subscription.
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
    static final Duration DEFAULT_GAP_TIMEOUT      = GapMiddlesAwaitedInMemory.DEFAULT_GAP_TIMEOUT;
    /**
     * Bound on the runs held above the watermark - each one stands for at least one gap below it - and on the earlier gaps
     */
    static final int      DEFAULT_MAX_TRACKED_GAPS = 10_000;
    /**
     * How many global orders at <i>each</i> end of a gap an event opens are awaited durably - recorded as transient gaps of
     * the subscriber, so a later subscription waits for them too: the lowest ones, right above the highest order delivered
     * before it, and the highest ones, right below the event. The orders between the two ends of a gap wider than twice
     * this are awaited <b>in memory only</b>: an event for one of them that arrives within {@code gapTimeout} is delivered
     * like any gap fill, but nothing records them, and once {@code gapTimeout} passed they are given up without writing
     * anything ({@link #drainTimedOutGapsAwaitedInMemoryOnly()}). They survive a re-subscribe of the subscriber on this
     * event store instance - the resume after a {@code SubscriptionErrorPolicy} stop, a stop and start: the subscription
     * hands the ones it still awaits on as it ends ({@link #handOverMiddlesAwaitedInMemoryOnly()}), each with its original
     * timeout, and the next one awaits them as earlier middles ({@link #seedEarlierMiddles}). A restart, crash,
     * {@code resetFrom}, unsubscribe or fenced-lock hand-over inside that window loses them - the subscriber's resume point
     * moved past them with the event - the same contract as a gap given up because of the cap.
     * <p>
     * Why the ends are the ones recorded: {@code global_event_order} comes from the event table's sequence. {@code nextval} hands out
     * orders in increasing order when a row is inserted and never takes one back, so an order in a gap is either
     * <ul>
     *     <li>held by a transaction still in flight. It took its orders from where the sequence stood at the time, next
     *     to the orders handed out just before and after them, so they lie at one of the two ends: right above the
     *     highest order delivered - taken before the event's, and before whatever moved the sequence forward (a
     *     {@code setval}, a restore) - or right below the event - taken by a concurrent writer after any such move,
     *     before the event's own; or</li>
     *     <li>never to be committed: burned by a rolled back transaction, or skipped by the sequence moving forward.
     *     Nothing fills it.</li>
     * </ul>
     * Only the first kind can still be delivered, and there are no more of them than the events the in-flight
     * transactions append - so the middle of a wide gap is almost always of the second kind, and awaiting it in memory
     * only costs nothing: the gap is one run whatever its width. Recorded in full, a gap of a million orders - a sequence
     * moved a million forward under a running subscription - had a million transient gaps written before the event was
     * handed on, and given up order by order once {@code gapTimeout} had passed.
     * <p>
     * The bound is {@link SubscriptionGapHandler#MAX_AWAITED_ORDERS_PER_GAP_END}, shared with the polling path, whose gap
     * handler records a gap's ends - and whose subscriptions await its middle in memory - the same way. It is half of
     * {@link #DEFAULT_MAX_TRACKED_GAPS}: one gap is recorded at most as many orders deep as the tracker waits for separate
     * gaps at once - far more than the events normally in flight on one event table. A gap no wider than twice this is
     * recorded in full.
     */
    static final int      MAX_AWAITED_ORDERS_PER_GAP_END = SubscriptionGapHandler.MAX_AWAITED_ORDERS_PER_GAP_END;

    /**
     * What {@link #markDelivered} found. For {@link Kind#OPENED_GAP} the gap is {@code [gapFromInclusive .. order - 1]},
     * of which {@code awaitedInMemoryOnly} - the middle of a gap wider than twice {@link #MAX_AWAITED_ORDERS_PER_GAP_END} -
     * is not to be recorded; for {@link Kind#FILLED_GAP} it is the order itself
     */
    record Delivery(Kind kind, long gapFromInclusive, Optional<LongRange> awaitedInMemoryOnly) {
        static final Delivery DUPLICATE = new Delivery(Kind.DUPLICATE, 0);
        static final Delivery IN_ORDER  = new Delivery(Kind.IN_ORDER, 0);

        Delivery(Kind kind, long gapFromInclusive) {
            this(kind, gapFromInclusive, Optional.empty());
        }

        boolean isNew() {
            return kind != Kind.DUPLICATE;
        }

        /**
         * For {@link Kind#OPENED_GAP}: the orders below {@code order} - the event's - to await durably, lowest first: the
         * whole gap, or the two ends of a wide one. Empty for any other kind
         */
        List<LongRange> durablyAwaitedGapsBelow(long order) {
            if (kind != Kind.OPENED_GAP) return List.of();
            return awaitedInMemoryOnly.map(middle -> List.of(LongRange.between(gapFromInclusive, middle.fromInclusive - 1),
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
     * The middles of wide gaps, awaited in memory only (see {@link #MAX_AWAITED_ORDERS_PER_GAP_END}), keyed by where each
     * starts - one entry per gap, disjoint, forgotten once the watermark passed them
     */
    private final TreeMap<Long, Long>               awaitedInMemoryOnly = new TreeMap<>();
    /**
     * The parts of {@link #awaitedInMemoryOnly} given up on after {@code gapTimeout} since the last
     * {@link #drainTimedOutGapsAwaitedInMemoryOnly()} - null while they are not collected
     */
    private List<LongRange>                         timedOutInMemoryOnly;
    /**
     * The orders of {@link #awaitedInMemoryOnly} delivered as gap fills that the subscriber is not done with yet, and since
     * when (in {@link #nanoClock} time) their gap had been awaited: handed on with the ranges still awaited, so a
     * subscription that ends before the subscriber handled one leaves it to the next
     */
    private final Map<Long, Long>                   inMemoryOnlyFillsNotDoneWith = new HashMap<>();
    /**
     * The middles an earlier subscription of the subscriber awaited below the watermark, awaited until their original
     * timeout - a standalone, empty instance until {@link #seedEarlierMiddles}. An order delivered from one stays in it
     * until the subscriber is done with it ({@link #doneWith}), so a subscription that ends first leaves it to the next
     */
    private GapMiddlesAwaitedInMemory               earlierMiddles;
    /**
     * The orders delivered from {@link #earlierMiddles} that the subscriber is not done with yet - not delivered again
     */
    private final Set<Long>                         earlierMiddleFillsNotDoneWith = new HashSet<>();
    /**
     * Whether either of the two above holds an order - read lock-free by {@link #doneWith}
     */
    private volatile boolean                        hasMiddleFillsNotDoneWith;
    /**
     * The lowest and highest order of {@link #earlierMiddles} - read lock-free by the bus thread, see
     * {@link #isAtOrBelowWatermarkAndNotAwaited}. Possibly wider than what is still awaited, never narrower
     */
    private volatile long                           earlierMiddlesFrom = Long.MAX_VALUE;
    private volatile long                           earlierMiddlesTo   = Long.MIN_VALUE;

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
        this.earlierMiddles = GapMiddlesAwaitedInMemory.awaitedFor(gapTimeout, nanoClock);
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
     * Wait for the orders of {@code middles} too, although they are at or below the watermark: the middles of wide gaps,
     * awaited in memory only, that an earlier subscription of the subscriber in this event store instance still awaited
     * when it ended - its resume point, where this one starts, lies above them (see {@link GapMiddlesAwaitedAcrossSubscribes},
     * which dropped those at or above it). Each is awaited until its original timeout. An event that fills one is
     * delivered as a gap fill, once; the order stays in {@code middles} until the subscriber is done with it
     * ({@link #doneWith}), so should this subscription end first, the next one waits for it again. Called once, before
     * anything is delivered.
     */
    synchronized void seedEarlierMiddles(GapMiddlesAwaitedInMemory middles) {
        earlierMiddles = requireNonNull(middles, "No middles provided");
        earlierMiddleFillsNotDoneWith.clear();
        middlesChanged();
        fillsNotDoneWithChanged();
        if (!middles.isEmpty()) {
            log.debug("[{}] Awaiting the middle(s) {} of wide gaps an earlier subscription awaited in memory, until their timeout", name, middles.awaited());
        }
    }

    /**
     * @return the earlier middles - see {@link #seedEarlierMiddles}
     */
    synchronized GapMiddlesAwaitedInMemory earlierMiddles() {
        return earlierMiddles;
    }

    /**
     * The subscriber is done with {@code events} - it handled them, gave up on them, or was handed them without
     * acknowledging: a middle fill among them is no longer owed to a later subscription
     */
    void doneWith(Collection<PersistedEvent> events) {
        requireNonNull(events, "No events provided");
        if (!hasMiddleFillsNotDoneWith) {
            // Read lock-free: called for every event the subscriber is done with, from its own thread
            return;
        }
        synchronized (this) {
            if (!inMemoryOnlyFillsNotDoneWith.isEmpty()) {
                events.forEach(event -> inMemoryOnlyFillsNotDoneWith.remove(event.globalEventOrder().longValue()));
            }
            if (!earlierMiddleFillsNotDoneWith.isEmpty()) {
                earlierMiddles.handled(events);
                events.forEach(event -> earlierMiddleFillsNotDoneWith.remove(event.globalEventOrder().longValue()));
                middlesChanged();
            }
            fillsNotDoneWithChanged();
        }
    }

    /**
     * After {@link #inMemoryOnlyFillsNotDoneWith} or {@link #earlierMiddleFillsNotDoneWith} changed
     */
    private void fillsNotDoneWithChanged() {
        hasMiddleFillsNotDoneWith = !inMemoryOnlyFillsNotDoneWith.isEmpty() || !earlierMiddleFillsNotDoneWith.isEmpty();
    }

    /**
     * The subscription ends: hand the middles of wide gaps it still awaits in memory only on to the subscriber's next
     * subscription, by adding them to the {@link #earlierMiddles} - the parts of each no event filled yet, and the fills
     * the subscriber is not done with - each with its original timeout. Gives up first on what timed out.
     */
    synchronized void handOverMiddlesAwaitedInMemoryOnly() {
        giveUpExpiredGaps();
        for (var inMemoryOnly : awaitedInMemoryOnly.entrySet()) {
            long from = Math.max(inMemoryOnly.getKey(), watermark + 1);
            long to   = inMemoryOnly.getValue();
            var  in   = runsAboveWatermark.floorEntry(from);
            if (in != null && in.getValue().end >= from) {
                from = in.getValue().end + 1;
            }
            for (var above = runsAboveWatermark.ceilingEntry(from); from <= to && above != null; above = runsAboveWatermark.higherEntry(above.getKey())) {
                // The orders from 'from' up to the run above them were not delivered - awaited since that run revealed them
                if (above.getKey() > from) {
                    earlierMiddles.await(LongRange.between(from, Math.min(above.getKey() - 1, to)), above.getValue().gapBelowSince);
                }
                from = above.getValue().end + 1;
            }
        }
        inMemoryOnlyFillsNotDoneWith.forEach((order, awaitedSince) -> earlierMiddles.await(LongRange.only(order), awaitedSince));
    }

    /**
     * Keep the orders of the gaps given up on after {@code gapTimeout} from now on, until {@link #drainTimedOutGaps()}
     * takes them - for a subscription that records the give-up with its gap handler. Without it they are not kept at
     * all.
     */
    synchronized void collectTimedOutGaps() {
        if (timedOut == null) {
            timedOut = new ArrayList<>();
            timedOutInMemoryOnly = new ArrayList<>();
        }
    }

    /**
     * Gives up first on the gaps whose {@code gapTimeout} has passed, so a subscription that is stopped while idle
     * drains them too.
     *
     * @return the ranges of orders given up on after waiting {@code gapTimeout} for them - the gaps below the highest
     * order delivered, and the earlier gaps - since the last call, lowest first per kind; none unless
     * {@link #collectTimedOutGaps()} was called. At most one range per end of a gap, each at most
     * {@link #MAX_AWAITED_ORDERS_PER_GAP_END} wide - or a narrower gap whole. Never a gap given up because of the cap, nor
     * the middle of a wide gap, which {@link #drainTimedOutGapsAwaitedInMemoryOnly()} drains (see the class javadoc)
     */
    synchronized List<LongRange> drainTimedOutGaps() {
        giveUpExpiredGaps();
        return drain(timedOut);
    }

    /**
     * Gives up first on the gaps whose {@code gapTimeout} has passed, as {@link #drainTimedOutGaps()} does.
     *
     * @return the ranges of the middles of wide gaps given up on after waiting {@code gapTimeout} for them since the last
     * call - awaited in memory only, never recorded as transient gaps, so there is nothing to record about giving them up
     * either (see {@link #MAX_AWAITED_ORDERS_PER_GAP_END}); none unless {@link #collectTimedOutGaps()} was called. Never a
     * part given up because of the cap
     */
    synchronized List<LongRange> drainTimedOutGapsAwaitedInMemoryOnly() {
        giveUpExpiredGaps();
        return drain(timedOutInMemoryOnly);
    }

    private static List<LongRange> drain(List<LongRange> collected) {
        if (collected == null || collected.isEmpty()) {
            return List.of();
        }
        var drained = List.copyOf(collected);
        collected.clear();
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
            delivery = earlierGaps.remove(order) || deliversEarlierMiddle(order) ? new Delivery(Kind.FILLED_GAP, order) : Delivery.DUPLICATE;
        } else if (order > highestDelivered) {
            delivery = deliverAboveHighest(order);
        } else {
            delivery = fillGap(order);
        }
        enforceCap();
        forgetInMemoryOnlyRangesAtOrBelowWatermark();
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
        // One run, however wide the gap below it
        runsAboveWatermark.put(order, new Run(order, now));
        if (gapTo - gapFrom + 1 > 2L * MAX_AWAITED_ORDERS_PER_GAP_END) {
            // Most likely held by no transaction that can still commit - see MAX_AWAITED_ORDERS_PER_GAP_END: awaited like
            // the rest of the gap, but marked so it is neither recorded nor given up with the gap handler
            var middle = LongRange.between(gapFrom + MAX_AWAITED_ORDERS_PER_GAP_END, gapTo - MAX_AWAITED_ORDERS_PER_GAP_END);
            awaitedInMemoryOnly.put(middle.fromInclusive, middle.getToInclusive());
            log.warn("[{}] Global order {} opened a gap of {} orders above {} - the global order sequence was moved forward, or a large append rolled back. " +
                             "Awaiting the {} lowest and the {} highest of them durably, and the {} in between ({}) in memory only: a restart within {} does not wait for those",
                     name, order, gapTo - gapFrom + 1, previousHighest, MAX_AWAITED_ORDERS_PER_GAP_END, MAX_AWAITED_ORDERS_PER_GAP_END,
                     middle.getToInclusive() - middle.fromInclusive + 1, middle, Duration.ofNanos(gapTimeoutNanos));
            return new Delivery(Kind.OPENED_GAP, gapFrom, Optional.of(middle));
        }
        return new Delivery(Kind.OPENED_GAP, gapFrom);
    }

    /**
     * @return true when {@code order} - at or below the watermark - is in an earlier middle and not delivered from it yet;
     * it is then delivered from now on
     */
    private boolean deliversEarlierMiddle(long order) {
        if (earlierMiddles.isAwaited(order) && earlierMiddleFillsNotDoneWith.add(order)) {
            hasMiddleFillsNotDoneWith = true;
            return true;
        }
        return false;
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
        if (isAwaitedInMemoryOnly(order)) {
            inMemoryOnlyFillsNotDoneWith.put(order, above.getValue().gapBelowSince);
            hasMiddleFillsNotDoneWith = true;
        }
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
            return !earlierGaps.contains(order) && !(earlierMiddles.isAwaited(order) && !earlierMiddleFillsNotDoneWith.contains(order));
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
        return order <= watermark && !earlierGaps.contains(order) && (order < earlierMiddlesFrom || order > earlierMiddlesTo);
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
        if (!earlierMiddles.isEmpty()) {
            var givenUp = earlierMiddles.dropTimedOut();
            if (!givenUp.isEmpty()) {
                log.debug("[{}] Gave up waiting for the middle(s) {} of wide gaps an earlier subscription awaited in memory - nothing to record", name, givenUp);
                middlesChanged();
            }
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
            collectTimedOut(watermark + 1, lowest.getKey() - 1);
        }
        watermark = lowest.getValue().end;
        forgetInMemoryOnlyRangesAtOrBelowWatermark();
    }

    /**
     * Collects the gap {@code [fromInclusive .. toInclusive]}, given up after {@code gapTimeout}: the parts of it awaited in
     * memory only for {@link #drainTimedOutGapsAwaitedInMemoryOnly()}, the rest for {@link #drainTimedOutGaps()}. Visits
     * only the in-memory-only ranges that overlap the gap - never its orders
     */
    private void collectTimedOut(long fromInclusive, long toInclusive) {
        long from       = fromInclusive;
        var  startingAt = awaitedInMemoryOnly.floorKey(fromInclusive);
        for (var inMemoryOnly : awaitedInMemoryOnly.tailMap(startingAt == null ? fromInclusive : startingAt, true).entrySet()) {
            if (inMemoryOnly.getKey() > toInclusive) break;
            long overlapFrom = Math.max(inMemoryOnly.getKey(), from);
            long overlapTo   = Math.min(inMemoryOnly.getValue(), toInclusive);
            if (overlapFrom > overlapTo) continue;
            if (from < overlapFrom) {
                timedOut.add(LongRange.between(from, overlapFrom - 1));
            }
            timedOutInMemoryOnly.add(LongRange.between(overlapFrom, overlapTo));
            from = overlapTo + 1;
        }
        if (from <= toInclusive) {
            timedOut.add(LongRange.between(from, toInclusive));
        }
    }

    private void forgetInMemoryOnlyRangesAtOrBelowWatermark() {
        while (!awaitedInMemoryOnly.isEmpty() && awaitedInMemoryOnly.firstEntry().getValue() <= watermark) {
            awaitedInMemoryOnly.pollFirstEntry();
        }
    }

    private boolean isAwaitedInMemoryOnly(long order) {
        var inMemoryOnly = awaitedInMemoryOnly.floorEntry(order);
        return inMemoryOnly != null && inMemoryOnly.getValue() >= order;
    }

    /**
     * After {@link #earlierMiddles} changed: the span the bus thread checks against
     */
    private void middlesChanged() {
        var span = earlierMiddles.span();
        earlierMiddlesFrom = span.map(range -> range.fromInclusive).orElse(Long.MAX_VALUE);
        earlierMiddlesTo = span.map(LongRange::getToInclusive).orElse(Long.MIN_VALUE);
        if (span.isEmpty()) {
            earlierMiddleFillsNotDoneWith.clear();
            fillsNotDoneWithChanged();
        }
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
                ", runsAboveWatermark=" + runsAboveWatermark.size() + ", earlierGaps=" + earlierGaps.size() +
                ", awaitedInMemoryOnly=" + awaitedInMemoryOnly.size() + ", earlierMiddles=" + earlierMiddles.awaited().size() + '}';
    }
}
