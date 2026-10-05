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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.SubscriptionGapHandler;
import dk.trustworks.essentials.types.LongRange;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * <b>Internal - not part of the public API</b>, and may change in any release: public only so the polling event store,
 * in another package, can use it.
 * <p>
 * The middles of the wide gaps one polling subscription awaits <b>in memory only</b> - see
 * {@link SubscriptionGapHandler#MAX_AWAITED_ORDERS_PER_GAP_END} and {@link GapEnds#awaitedInMemoryOnly()}. No row records
 * them: each poll re-queries them by range until their timeout has passed, and an event found in one is delivered as a gap
 * fill and taken out of it ({@link #delivered(long)}), so it is never delivered twice. After the timeout a middle is
 * dropped ({@link #dropTimedOut()}), still without writing anything. A restart or crash inside that window loses it.
 * <p>
 * Each range is one entry, keyed by where it starts, whatever its width: delivering an order splits its entry in
 * {@code O(log n)} of the entries, never in its width. The CDC event store's delivery tracker keeps its middles beside its
 * delivered runs instead; its structure answers "was this order delivered" for every order above its watermark, which a
 * poll - reading the stream in global order - has no need for, so the two are kept apart rather than coupling polling to
 * the CDC package.
 * <p>
 * Thread-safety: every method is {@code synchronized}; a subscription's polls run one at a time, but on more than one thread.
 */
public final class GapMiddlesAwaitedInMemory {
    /**
     * How long a middle is awaited when the subscription's gap handler does not state its give-up threshold - the default
     * permanent-gap threshold of the {@code PostgresqlEventStreamGapHandler}, as on the CDC path
     */
    public static final Duration DEFAULT_GAP_TIMEOUT = Duration.ofSeconds(120);

    private record Awaited(long toInclusive, long deadlineNanos) {
    }

    private final long                     timeoutNanos;
    private final LongSupplier             nanoClock;
    private final TreeMap<Long, Awaited>   byFromInclusive = new TreeMap<>();
    /**
     * The highest order ever awaited - see {@link #await(LongRange)}
     */
    private       long                     awaitedUpToInclusive = Long.MIN_VALUE;

    /**
     * @param gapHandler the subscription's gap handler: a middle is awaited for its
     *                   {@link SubscriptionGapHandler#transientGapGiveUpThreshold()}, else {@link #DEFAULT_GAP_TIMEOUT}
     */
    public static GapMiddlesAwaitedInMemory awaitedAsLongAs(SubscriptionGapHandler gapHandler) {
        return new GapMiddlesAwaitedInMemory(requireNonNull(gapHandler, "No gapHandler provided").transientGapGiveUpThreshold().orElse(DEFAULT_GAP_TIMEOUT),
                                             System::nanoTime);
    }

    GapMiddlesAwaitedInMemory(Duration timeout, LongSupplier nanoClock) {
        this.timeoutNanos = requireNonNull(timeout, "No timeout provided").toNanos();
        this.nanoClock = requireNonNull(nanoClock, "No nanoClock provided");
    }

    /**
     * @return how long a middle is awaited
     */
    public Duration timeout() {
        return Duration.ofNanos(timeoutNanos);
    }

    /**
     * Await {@code middle} from now on, for the timeout - the part of it above every order awaited before. A hole a poll
     * reveals lies above the read position, and so above every middle awaited before; a range that does not is one
     * revealed again - by a poll repeated from a read position that did not move, e.g. after a cancel - and its orders
     * were awaited already, and may have been delivered: awaited again, they could be delivered twice.
     *
     * @param middle a closed range of orders no row records
     */
    public synchronized void await(LongRange middle) {
        requireNonNull(middle, "No middle provided");
        requireTrue(middle.isClosedRange(), msg("Range {} is not closed", middle));
        long from = Math.max(middle.fromInclusive, awaitedUpToInclusive == Long.MIN_VALUE ? Long.MIN_VALUE : awaitedUpToInclusive + 1);
        long to   = middle.getToInclusive();
        if (from > to) {
            return;
        }
        byFromInclusive.put(from, new Awaited(to, nanoClock.getAsLong() + timeoutNanos));
        awaitedUpToInclusive = to;
    }

    /**
     * @return whether no middle is awaited
     */
    public synchronized boolean isEmpty() {
        return byFromInclusive.isEmpty();
    }

    /**
     * @return the awaited ranges, lowest first
     */
    public synchronized List<LongRange> awaited() {
        return byFromInclusive.entrySet()
                              .stream()
                              .map(entry -> LongRange.between(entry.getKey(), entry.getValue().toInclusive))
                              .toList();
    }

    /**
     * @return whether {@code order} is awaited
     */
    public synchronized boolean isAwaited(long order) {
        var entry = byFromInclusive.floorEntry(order);
        return entry != null && entry.getValue().toInclusive >= order;
    }

    /**
     * Stop awaiting {@code order}: its event was read, and is being delivered. Splits the range it is in
     *
     * @return whether it was awaited
     */
    public synchronized boolean delivered(long order) {
        var entry = byFromInclusive.floorEntry(order);
        if (entry == null || entry.getValue().toInclusive < order) {
            return false;
        }
        var awaited = entry.getValue();
        byFromInclusive.remove(entry.getKey());
        if (entry.getKey() < order) {
            byFromInclusive.put(entry.getKey(), new Awaited(order - 1, awaited.deadlineNanos));
        }
        if (order < awaited.toInclusive) {
            byFromInclusive.put(order + 1, new Awaited(awaited.toInclusive, awaited.deadlineNanos));
        }
        return true;
    }

    /**
     * Stop awaiting the ranges whose timeout has passed
     *
     * @return the ranges dropped, lowest first
     */
    public synchronized List<LongRange> dropTimedOut() {
        if (byFromInclusive.isEmpty()) {
            return List.of();
        }
        long now     = nanoClock.getAsLong();
        var  dropped = new ArrayList<LongRange>();
        var  entries = byFromInclusive.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (now - entry.getValue().deadlineNanos >= 0) {
                dropped.add(LongRange.between(entry.getKey(), entry.getValue().toInclusive));
                entries.remove();
            }
        }
        return dropped;
    }

    @Override
    public synchronized String toString() {
        return "GapMiddlesAwaitedInMemory{" + awaited() + ", timeout=" + timeout() + '}';
    }
}
