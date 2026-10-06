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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.internal;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.SubscriptionGapHandler;
import dk.trustworks.essentials.types.LongRange;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * <b>Internal - not part of the public API</b>, and may change in any release: public only so the polling event store
 * and the CDC event store, in other packages, can use it.
 * <p>
 * The middles of the wide gaps one subscribe of a subscription awaits <b>in memory only</b> - see
 * {@link SubscriptionGapHandler#MAX_AWAITED_ORDERS_PER_GAP_END} and {@link GapEnds#awaitedInMemoryOnly()}. No row records
 * them: each poll re-queries them by range until their timeout has passed, and an event found in one is delivered as a gap
 * fill and stops being awaited once the subscriber is done with it ({@link #handled}) - or as soon as it is read, when it
 * is not the subscriber's to handle ({@link #delivered(long)}) - so it is never delivered twice, and never lost by a
 * subscribe that read it and ended before the subscriber was done with it. After the timeout a middle is dropped
 * ({@link #dropTimedOut()}), still without writing anything.
 * <p>
 * The ranges outlive the subscribe: {@link GapMiddlesAwaitedAcrossSubscribes} hands them to the next subscribe of the same
 * subscriber and aggregate type in this event store instance - the resume after a {@code SubscriptionErrorPolicy} stop, a
 * stop and start - which goes on awaiting the middles below where it starts reading, each until its original timeout,
 * and drops those at or above it: it reads them in global order anyway. A restart or crash, a {@code resetFrom}, an
 * unsubscribe or a fenced-lock hand-over inside that window loses them.
 * <p>
 * Each range is one entry, keyed by where it starts, whatever its width: delivering an order splits its entry in
 * {@code O(log n)} of the entries, never in its width. The CDC event store's delivery tracker keeps the middles it finds
 * beside its delivered runs instead; its structure answers "was this order delivered" for every order above its
 * watermark, which a poll - reading the stream in global order - has no need for. It uses this class only for the middles
 * an earlier subscribe handed on, which lie below its watermark.
 * <p>
 * Thread-safety: every method holds the monitor of the shared ranges. A subscription's polls run one at a time, but on more
 * than one thread; a subscriber acknowledges on its own thread; and a subscribe that ended may still be finishing a poll
 * while the next one starts. Only the subscribe that holds the ranges - the latest to start, until it ends - awaits a new
 * middle ({@link #await}) or stops awaiting one it read ({@link #delivered}): what an ended subscribe's last poll found is
 * read again by the next subscribe, from where that one starts. That the subscriber is done with an event
 * ({@link #handled}) holds whichever subscribe handed it on.
 */
public final class GapMiddlesAwaitedInMemory {
    /**
     * How long a middle is awaited when the subscription's gap handler does not state its give-up threshold - the default
     * permanent-gap threshold of the {@code PostgresqlEventStreamGapHandler}, as on the CDC path
     */
    public static final Duration DEFAULT_GAP_TIMEOUT = Duration.ofSeconds(120);

    /**
     * @param toInclusive  where the range ends
     * @param awaitedSince when it started to be awaited (in the ranges' clock)
     * @param timeoutNanos how long it is awaited - the timeout of the subscribe that started to await it
     */
    private record Middle(long toInclusive, long awaitedSince, long timeoutNanos) {
        Middle endingAt(long toInclusive) {
            return new Middle(toInclusive, awaitedSince, timeoutNanos);
        }

        boolean hasTimedOut(long now) {
            return now - awaitedSince >= timeoutNanos;
        }
    }

    /**
     * The awaited ranges themselves - shared by the subscribes of one subscriber that {@link GapMiddlesAwaitedAcrossSubscribes}
     * hands them on between. Guarded by its own monitor
     */
    static final class Ranges {
        private final LongSupplier             nanoClock;
        private final TreeMap<Long, Middle>    byFromInclusive      = new TreeMap<>();
        /**
         * The highest order awaited since the latest subscribe started - see {@link #await(LongRange)}
         */
        private       long                     awaitedUpToInclusive = Long.MIN_VALUE;
        /**
         * The subscribe that awaits new middles in these ranges: the latest to start, until it ends - null then
         */
        private       GapMiddlesAwaitedInMemory heldBy;

        Ranges(LongSupplier nanoClock) {
            this.nanoClock = requireNonNull(nanoClock, "No nanoClock provided");
        }

        /**
         * Stop awaiting every order at or above {@code fromInclusive} - a subscribe reading from there reads them in
         * global order - and let it await again what it finds there
         */
        private void notAwaitingFrom(long fromInclusive) {
            byFromInclusive.tailMap(fromInclusive, true).clear();
            var below = byFromInclusive.lowerEntry(fromInclusive);
            if (below != null && below.getValue().toInclusive >= fromInclusive) {
                byFromInclusive.put(below.getKey(), below.getValue().endingAt(fromInclusive - 1));
            }
            awaitedUpToInclusive = Math.min(awaitedUpToInclusive, fromInclusive - 1);
        }

        private List<LongRange> dropTimedOut() {
            if (byFromInclusive.isEmpty()) {
                return List.of();
            }
            long now     = nanoClock.getAsLong();
            var  dropped = new ArrayList<LongRange>();
            var  entries = byFromInclusive.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (entry.getValue().hasTimedOut(now)) {
                    dropped.add(LongRange.between(entry.getKey(), entry.getValue().toInclusive));
                    entries.remove();
                }
            }
            return dropped;
        }

        /**
         * @return true when no subscribe holds these ranges and nothing in them is awaited any more, after dropping what
         * timed out - they can be forgotten
         */
        synchronized boolean isForgettable() {
            if (heldBy != null) {
                return false;
            }
            dropTimedOut();
            return byFromInclusive.isEmpty();
        }

        /**
         * Stop awaiting anything, and stop the subscribe that holds the ranges, if one does, from awaiting anything new
         */
        synchronized void forget() {
            byFromInclusive.clear();
            heldBy = null;
        }

        /**
         * Await the part of {@code [fromInclusive .. toInclusive]} no entry covers yet
         */
        private void awaitUncovered(long fromInclusive, long toInclusive, long awaitedSince, long timeoutNanos) {
            long from        = fromInclusive;
            var  startingAt  = byFromInclusive.floorKey(fromInclusive);
            // A copy: the loop adds entries
            var  overlapping = new ArrayList<>(byFromInclusive.subMap(startingAt == null ? fromInclusive : startingAt, true, toInclusive, true).entrySet());
            for (var entry : overlapping) {
                if (entry.getValue().toInclusive < from) continue;
                if (entry.getKey() > from) {
                    byFromInclusive.put(from, new Middle(entry.getKey() - 1, awaitedSince, timeoutNanos));
                }
                from = entry.getValue().toInclusive + 1;
                if (from > toInclusive) return;
            }
            byFromInclusive.put(from, new Middle(toInclusive, awaitedSince, timeoutNanos));
        }
    }

    private final Ranges ranges;
    private final long   timeoutNanos;
    /**
     * What {@link GapMiddlesAwaitedAcrossSubscribes} keeps the ranges under - null for a standalone instance
     */
    private final Object key;

    /**
     * A standalone instance, which hands its middles on to no one
     *
     * @param gapHandler the subscription's gap handler: a middle is awaited for {@link #timeoutFor(SubscriptionGapHandler)}
     */
    public static GapMiddlesAwaitedInMemory awaitedAsLongAs(SubscriptionGapHandler gapHandler) {
        return awaitedFor(timeoutFor(gapHandler), System::nanoTime);
    }

    /**
     * A standalone instance, which hands its middles on to no one
     *
     * @param timeout   how long a middle is awaited
     * @param nanoClock the clock middles are aged by - {@link System#nanoTime()} outside tests
     */
    public static GapMiddlesAwaitedInMemory awaitedFor(Duration timeout, LongSupplier nanoClock) {
        return new GapMiddlesAwaitedInMemory(timeout, nanoClock);
    }

    /**
     * @param gapHandler the subscription's gap handler
     * @return how long a subscription with that gap handler awaits a middle: its
     * {@link SubscriptionGapHandler#transientGapGiveUpThreshold()}, else {@link #DEFAULT_GAP_TIMEOUT}
     */
    public static Duration timeoutFor(SubscriptionGapHandler gapHandler) {
        return requireNonNull(gapHandler, "No gapHandler provided").transientGapGiveUpThreshold().orElse(DEFAULT_GAP_TIMEOUT);
    }

    GapMiddlesAwaitedInMemory(Duration timeout, LongSupplier nanoClock) {
        this(new Ranges(nanoClock), timeout, null);
        ranges.heldBy = this;
    }

    private GapMiddlesAwaitedInMemory(Ranges ranges, Duration timeout, Object key) {
        this.ranges = ranges;
        requireNonNull(timeout, "No timeout provided");
        // Saturating: a threshold of centuries must not overflow
        this.timeoutNanos = timeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : timeout.toNanos();
        this.key = key;
    }

    /**
     * A subscribe that starts reading at {@code fromInclusive} takes the {@code ranges} over: from now on it awaits the
     * new middles there, and the ones awaited before below where it starts - those at or above it are dropped, as it
     * reads them in global order. Called by {@link GapMiddlesAwaitedAcrossSubscribes}
     */
    static GapMiddlesAwaitedInMemory takingOver(Ranges ranges, Duration timeout, Object key, long fromInclusive) {
        var subscribe = new GapMiddlesAwaitedInMemory(ranges, timeout, key);
        synchronized (ranges) {
            ranges.notAwaitingFrom(fromInclusive);
            ranges.heldBy = subscribe;
        }
        return subscribe;
    }

    /**
     * This subscribe ended: it no longer awaits anything new, and what it still awaits is left for the next subscribe.
     * Called by {@link GapMiddlesAwaitedAcrossSubscribes}
     *
     * @return true when no subscribe holds the ranges and nothing in them is awaited any more
     */
    boolean ended() {
        synchronized (ranges) {
            if (ranges.heldBy == this) {
                ranges.heldBy = null;
            }
        }
        return ranges.isForgettable();
    }

    Ranges ranges() {
        return ranges;
    }

    Object key() {
        return key;
    }

    /**
     * @return how long a middle this subscribe starts to await is awaited
     */
    public Duration timeout() {
        return Duration.ofNanos(timeoutNanos);
    }

    /**
     * Await {@code middle} from now on, for the timeout - the part of it above every order awaited before since this
     * subscribe started. A hole a poll reveals lies above the read position, and so above every middle awaited before; a
     * range that does not is one revealed again - by a poll repeated from a read position that did not move, e.g. after
     * a cancel - and its orders were awaited already, and may have been delivered: awaited again, they could be delivered
     * twice. Ignored once this subscribe ended, or another one took the ranges over.
     *
     * @param middle a closed range of orders no row records
     */
    public void await(LongRange middle) {
        requireNonNull(middle, "No middle provided");
        requireTrue(middle.isClosedRange(), msg("Range {} is not closed", middle));
        synchronized (ranges) {
            if (ranges.heldBy != this) {
                return;
            }
            long from = Math.max(middle.fromInclusive, ranges.awaitedUpToInclusive == Long.MIN_VALUE ? Long.MIN_VALUE : ranges.awaitedUpToInclusive + 1);
            long to   = middle.getToInclusive();
            if (from > to) {
                return;
            }
            ranges.byFromInclusive.put(from, new Middle(to, ranges.nanoClock.getAsLong(), timeoutNanos));
            ranges.awaitedUpToInclusive = to;
        }
    }

    /**
     * Await {@code middle} for this subscribe's timeout counted from {@code awaitedSince}, rather than from now - a middle
     * a CDC subscription found itself, handed on as it ends. Only the orders of it not awaited yet are added. Ignored once
     * this subscribe ended, or another one took the ranges over.
     *
     * @param middle       a closed range of orders no row records
     * @param awaitedSince when the subscription started to await it, in the clock these ranges are aged by
     */
    public void await(LongRange middle, long awaitedSince) {
        requireNonNull(middle, "No middle provided");
        requireTrue(middle.isClosedRange(), msg("Range {} is not closed", middle));
        synchronized (ranges) {
            if (ranges.heldBy != this) {
                return;
            }
            ranges.awaitUncovered(middle.fromInclusive, middle.getToInclusive(), awaitedSince, timeoutNanos);
            ranges.awaitedUpToInclusive = Math.max(ranges.awaitedUpToInclusive, middle.getToInclusive());
        }
    }

    /**
     * @return whether no middle is awaited
     */
    public boolean isEmpty() {
        synchronized (ranges) {
            return ranges.byFromInclusive.isEmpty();
        }
    }

    /**
     * @return the awaited ranges, lowest first
     */
    public List<LongRange> awaited() {
        synchronized (ranges) {
            return ranges.byFromInclusive.entrySet()
                                         .stream()
                                         .map(entry -> LongRange.between(entry.getKey(), entry.getValue().toInclusive))
                                         .toList();
        }
    }

    /**
     * @return the range from the lowest to the highest order awaited - empty when none is
     */
    public Optional<LongRange> span() {
        synchronized (ranges) {
            if (ranges.byFromInclusive.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(LongRange.between(ranges.byFromInclusive.firstKey(), ranges.byFromInclusive.lastEntry().getValue().toInclusive));
        }
    }

    /**
     * @return whether {@code order} is awaited
     */
    public boolean isAwaited(long order) {
        synchronized (ranges) {
            var entry = ranges.byFromInclusive.floorEntry(order);
            return entry != null && entry.getValue().toInclusive >= order;
        }
    }

    /**
     * Stop awaiting {@code order}: this subscribe read its event, and the subscriber never has to handle it - another
     * tenant's - or it is being handed on by a source that records it as delivered. Splits the range it is in. Ignored
     * once this subscribe ended, or another one took the ranges over: then the next subscribe reads it again.
     *
     * @return whether it was awaited, and no longer is
     */
    public boolean delivered(long order) {
        synchronized (ranges) {
            if (ranges.heldBy != this) {
                return false;
            }
            return stopAwaiting(order);
        }
    }

    /**
     * Stop awaiting the orders of {@code events}: the subscriber is done with them - handled them, or they were handed to
     * a subscriber that does not acknowledge. Also when this subscribe has ended meanwhile: the next one must not deliver
     * them again
     */
    public void handled(Collection<PersistedEvent> events) {
        requireNonNull(events, "No events provided");
        synchronized (ranges) {
            if (ranges.byFromInclusive.isEmpty()) {
                return;
            }
            events.forEach(event -> stopAwaiting(event.globalEventOrder().longValue()));
        }
    }

    /**
     * Holding the ranges' monitor
     */
    private boolean stopAwaiting(long order) {
        var entry = ranges.byFromInclusive.floorEntry(order);
        if (entry == null || entry.getValue().toInclusive < order) {
            return false;
        }
        var awaited = entry.getValue();
        ranges.byFromInclusive.remove(entry.getKey());
        if (entry.getKey() < order) {
            ranges.byFromInclusive.put(entry.getKey(), awaited.endingAt(order - 1));
        }
        if (order < awaited.toInclusive) {
            ranges.byFromInclusive.put(order + 1, awaited);
        }
        return true;
    }

    /**
     * Stop awaiting the ranges whose timeout has passed
     *
     * @return the ranges dropped, lowest first
     */
    public List<LongRange> dropTimedOut() {
        synchronized (ranges) {
            return ranges.dropTimedOut();
        }
    }

    @Override
    public String toString() {
        return "GapMiddlesAwaitedInMemory{" + awaited() + ", timeout=" + timeout() + '}';
    }
}
