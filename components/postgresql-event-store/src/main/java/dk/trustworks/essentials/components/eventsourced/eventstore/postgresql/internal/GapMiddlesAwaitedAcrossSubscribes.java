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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * <b>Internal - not part of the public API</b>, and may change in any release: public only so the polling event store
 * and the CDC event store, in other packages, can use it.
 * <p>
 * The middles of wide gaps one event store instance awaits in memory only ({@link GapMiddlesAwaitedInMemory}), per
 * subscriber and aggregate type - so they outlive the subscribe that found them. Every path that disposes a running
 * subscription and subscribes it again - the resume after a {@code SubscriptionErrorPolicy} stop, which with the default
 * policy comes 10 s after a handler failed, well inside a middle's timeout, or a stop and start - starts a new subscribe at
 * the saved resume point, which lies above the middles. Each subscribe having its own, a late commit into one of them was
 * delivered by none, and no gap row recorded it.
 * <p>
 * A subscribe {@link #subscribe takes} the middles still awaited below where it starts reading - each keeping its
 * original timeout - and drops those at or above it, which it reads in global order anyway; when it ends
 * ({@link #subscribeEnded}) it leaves what it still awaits for the next. Not across event store instances: a restart, a
 * crash or a fenced-lock hand-over to another node loses them. A {@code resetFrom}, an unsubscribe and a fenced-lock
 * release {@link #forget} them - the resume point moved deliberately, or the subscription is gone.
 * <p>
 * Bounded: an entry lives while a subscribe holds it, and afterwards only while it still awaits a middle that has not
 * timed out - checked whenever a subscribe starts or ends, and by {@link #forgetTimedOut()}.
 * <p>
 * Thread-safety: an entry is created, taken over and removed inside the map's atomic {@code compute} for its key, which
 * then takes the entry's monitor - never the other way round.
 */
public final class GapMiddlesAwaitedAcrossSubscribes {
    /**
     * How often {@link #forgetTimedOut()} looks at every entry at most
     */
    private static final long SWEEP_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

    private record Key(SubscriberId subscriberId, AggregateType aggregateType) {
    }

    private final ConcurrentMap<Key, GapMiddlesAwaitedInMemory.Ranges> bySubscriber = new ConcurrentHashMap<>();
    private final LongSupplier                                         nanoClock;
    private final AtomicLong                                           lastSweep;

    /**
     * Ages middles by {@link System#nanoTime()}
     */
    public GapMiddlesAwaitedAcrossSubscribes() {
        this(System::nanoTime);
    }

    /**
     * @param nanoClock the clock middles are aged by - {@link System#nanoTime()} outside tests
     */
    public GapMiddlesAwaitedAcrossSubscribes(LongSupplier nanoClock) {
        this.nanoClock = requireNonNull(nanoClock, "No nanoClock provided");
        this.lastSweep = new AtomicLong(nanoClock.getAsLong());
    }

    /**
     * A subscribe of {@code subscriberId} to {@code aggregateType} starts reading at {@code fromInclusiveGlobalOrder}: it
     * takes over the middles the subscriber's earlier subscribes still await below that, and awaits the ones it finds
     * itself. Call {@link #subscribeEnded} when it ends.
     *
     * @param timeout how long a middle this subscribe starts to await is awaited - see
     *                {@link GapMiddlesAwaitedInMemory#timeoutFor}
     */
    public GapMiddlesAwaitedInMemory subscribe(SubscriberId subscriberId, AggregateType aggregateType, long fromInclusiveGlobalOrder, Duration timeout) {
        var key = new Key(requireNonNull(subscriberId, "No subscriberId provided"), requireNonNull(aggregateType, "No aggregateType provided"));
        requireNonNull(timeout, "No timeout provided");
        sweep();
        var subscribe = new AtomicReference<GapMiddlesAwaitedInMemory>();
        bySubscriber.compute(key, (k, ranges) -> {
            var takenOver = ranges != null ? ranges : new GapMiddlesAwaitedInMemory.Ranges(nanoClock);
            subscribe.set(GapMiddlesAwaitedInMemory.takingOver(takenOver, timeout, k, fromInclusiveGlobalOrder));
            return takenOver;
        });
        return subscribe.get();
    }

    /**
     * {@code subscribe} - returned by {@link #subscribe} - ended: it awaits nothing new from now on, and what it still
     * awaits is left for the subscriber's next subscribe. The entry is forgotten once nothing is awaited any more.
     */
    public void subscribeEnded(GapMiddlesAwaitedInMemory subscribe) {
        requireNonNull(subscribe, "No subscribe provided");
        if (!(subscribe.key() instanceof Key key)) {
            return;
        }
        bySubscriber.compute(key, (k, ranges) -> {
            var forgettable = subscribe.ended();
            if (ranges != subscribe.ranges()) {
                // Forgotten meanwhile - and maybe taken anew since
                return ranges;
            }
            return forgettable ? null : ranges;
        });
        sweep();
    }

    /**
     * Stop awaiting every middle of {@code subscriberId}'s subscribes to {@code aggregateType} - a {@code resetFrom} moved
     * its resume point, or it was unsubscribed or lost its fenced lock. A subscribe that still holds them awaits nothing
     * new afterwards either; the next one starts afresh.
     */
    public void forget(SubscriberId subscriberId, AggregateType aggregateType) {
        var forgotten = bySubscriber.remove(new Key(requireNonNull(subscriberId, "No subscriberId provided"), requireNonNull(aggregateType, "No aggregateType provided")));
        if (forgotten != null) {
            forgotten.forget();
        }
    }

    /**
     * Forget the entries no subscribe holds and whose middles all timed out - at most once a second, so it can be called on
     * every poll
     */
    public void forgetTimedOut() {
        long last = lastSweep.get();
        if (nanoClock.getAsLong() - last < SWEEP_INTERVAL_NANOS || !lastSweep.compareAndSet(last, nanoClock.getAsLong())) {
            return;
        }
        sweepNow();
    }

    /**
     * @return whether anything is kept for {@code subscriberId} and {@code aggregateType} - awaited, or held by a subscribe
     */
    public boolean isKeptFor(SubscriberId subscriberId, AggregateType aggregateType) {
        return bySubscriber.containsKey(new Key(subscriberId, aggregateType));
    }

    /**
     * @return how many subscriber and aggregate type pairs anything is kept for
     */
    public int size() {
        return bySubscriber.size();
    }

    private void sweep() {
        lastSweep.set(nanoClock.getAsLong());
        sweepNow();
    }

    private void sweepNow() {
        if (bySubscriber.isEmpty()) {
            return;
        }
        bySubscriber.keySet().forEach(key -> bySubscriber.computeIfPresent(key, (k, ranges) -> ranges.isForgettable() ? null : ranges));
    }

    @Override
    public String toString() {
        return "GapMiddlesAwaitedAcrossSubscribes{" + bySubscriber.keySet() + '}';
    }
}
