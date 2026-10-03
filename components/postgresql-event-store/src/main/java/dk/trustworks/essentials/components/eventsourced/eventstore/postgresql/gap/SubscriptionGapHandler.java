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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.types.LongRange;

import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * Handle event stream gaps handling for a specific {@link SubscriberId}
 */
public interface SubscriptionGapHandler {
    /**
     * The id of the subscriber that we're handling gaps on behalf of
     *
     * @return the id of the subscriber that we're handling gaps on behalf of
     */
    SubscriberId subscriberId();

    /**
     * Based on the <code>globalOrderQueryRange</code> resolve which transient gaps that should be included in the {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)}
     *
     * @param aggregateType         the aggregate type we want to resolve gaps for
     * @param globalOrderQueryRange the global order query range being used when querying for new events
     * @return a list of {@link GlobalEventOrder} gaps (can be null or empty if no transient gaps exists for this subscriber)
     */
    List<GlobalEventOrder> findTransientGapsToIncludeInQuery(AggregateType aggregateType,
                                                             LongRange globalOrderQueryRange);

    /**
     * Reconcile the <code>persistedEvents</code> returned from {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)}
     * against the <code>globalOrderRange</code> and <code>transientGapsIncludedInQuery</code> (returned by {@link #findTransientGapsToIncludeInQuery(AggregateType, LongRange)})
     * that were used in the {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)} query
     * <br>
     * This method is responsible for resolving transient transientGapsIncludedInQuery and promoting transientGapsIncludedInQuery to be permanent transientGapsIncludedInQuery in case the permanent gap criteria is met
     *
     * @param aggregateType                the aggregate type we want to reconcile transientGapsIncludedInQuery for
     * @param globalOrderQueryRange        the globalOrderRange used in the call to {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)}
     * @param persistedEvents              the returned from {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)}
     * @param transientGapsIncludedInQuery the gap, returned by {@link #findTransientGapsToIncludeInQuery(AggregateType, LongRange)}) using the same <code>globalOrderRange</code> input parameter, that was used in the
     *                                     call to {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)}
     */
    void reconcileGaps(AggregateType aggregateType, LongRange globalOrderQueryRange, List<PersistedEvent> persistedEvents, List<GlobalEventOrder> transientGapsIncludedInQuery);

    /**
     * {@link #reconcileGaps(AggregateType, LongRange, List, List)}, reporting what the reconciliation changed.
     * <p>
     * This is what the event store calls, so subscription statistics can say how many gaps a subscriber found, saw
     * resolve and gave up on - rather than only how often it reconciled, which is once per poll whether or not there
     * was a gap. The default performs the reconciliation and reports {@link GapReconciliation#NONE}, so an
     * implementation that does not override it keeps working and contributes nothing to those counts.
     *
     * @param aggregateType                the aggregate type we want to reconcile gaps for
     * @param globalOrderQueryRange        the globalOrderRange used in the call to {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)}
     * @param persistedEvents              the events returned from {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)}
     * @param transientGapsIncludedInQuery the gaps, returned by {@link #findTransientGapsToIncludeInQuery(AggregateType, LongRange)}, that were included in that query
     * @return what the reconciliation changed for this subscriber
     */
    default GapReconciliation reconcileGapsAndReport(AggregateType aggregateType,
                                                     LongRange globalOrderQueryRange,
                                                     List<PersistedEvent> persistedEvents,
                                                     List<GlobalEventOrder> transientGapsIncludedInQuery) {
        reconcileGaps(aggregateType, globalOrderQueryRange, persistedEvents, transientGapsIncludedInQuery);
        return GapReconciliation.NONE;
    }

    /**
     * Resolve the transient gaps that the given <b>gap fills</b> fill - and change nothing else: no new gaps are recorded
     * and no gap is promoted to a permanent one.
     * <p>
     * A gap fill is an event whose global order this subscriber recorded as a transient gap - it committed after events
     * with a higher global order had been delivered. The event store leaves such a gap open while it delivers the fill,
     * and resolves it through this method once the subscriber is done with the event: when it acknowledged it (see
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.SubscriberAcknowledgement}), or, for
     * a subscriber that does not acknowledge, once the event was handed on. An acknowledged fill is resolved in the
     * subscriber's unit of work, atomically with its handling, so this method runs inside whatever unit of work is
     * current - possibly on the subscriber's thread while the poll that delivered the fill reconciles on its own. The
     * event store serializes its calls to one {@link SubscriptionGapHandler}.
     * <p>
     * The default implementation reconciles as a query over the highest of the fills, that asked for exactly their gaps,
     * would: {@link #reconcileGapsAndReport(AggregateType, LongRange, List, List)} with {@link LongRange#only(long)} of the
     * highest fill, the fills, and their global orders - which, as every reconciliation, may also promote gaps.
     * The {@link PostgresqlEventStreamGapHandler}'s handlers override it to only delete the fills' transient gaps.
     *
     * @param aggregateType the aggregate type the fills belong to
     * @param gapFills      the gap fills the subscriber is done with - events whose global order is (or was) a transient gap
     *                      of this subscriber
     * @return what the resolution changed for this subscriber - the number of transient gaps it resolved
     */
    default GapReconciliation resolveFilledGaps(AggregateType aggregateType, List<PersistedEvent> gapFills) {
        if (gapFills.isEmpty()) {
            return GapReconciliation.NONE;
        }
        var filledGaps = gapFills.stream().map(PersistedEvent::globalEventOrder).toList();
        var highest    = filledGaps.stream().mapToLong(GlobalEventOrder::longValue).max().getAsLong();
        return reconcileGapsAndReport(aggregateType, LongRange.only(highest), gapFills, filledGaps);
    }

    /**
     * Record that the subscription gave up waiting for these transient gaps' events - so the next subscription of this
     * subscriber does not wait for them again, and drops an event for one of them that commits late, as the subscription
     * that gave up does.
     * <p>
     * Called by a subscription that tracks gaps itself - the CDC event store's delivery tracker - once it has waited
     * {@link #transientGapGiveUpThreshold()} for a gap without its event arriving, from the CDC bus or a poll. That wait
     * is its proof that the event is missing, standing in for the query that asked for the gap and did not get it, which
     * is what {@link #reconcileGapsAndReport(AggregateType, LongRange, List, List)} promotes on. The event store calls it
     * inside a unit of work of its own, holding this handler's monitor, never across the commit.
     * <p>
     * The default implementation reconciles as a query that asked for exactly these gaps and found none of their events
     * would: {@link #reconcileGapsAndReport(AggregateType, LongRange, List, List)} with no events - promoting the ones the
     * handler's own rule considers ready. The {@link PostgresqlEventStreamGapHandler}'s handlers override it to promote
     * each of them that is still a transient gap, when their promotion strategy states the threshold that was waited.
     *
     * @param aggregateType the aggregate type the gaps belong to
     * @param transientGaps the global orders given up on - transient gaps of this subscriber, unless something else
     *                      resolved or promoted them meanwhile
     * @return what recording the give-up changed for this subscriber - the number of transient gaps promoted
     */
    default GapReconciliation giveUpTransientGaps(AggregateType aggregateType, List<GlobalEventOrder> transientGaps) {
        if (transientGaps.isEmpty()) {
            return GapReconciliation.NONE;
        }
        var highest = transientGaps.stream().mapToLong(GlobalEventOrder::longValue).max().getAsLong();
        return reconcileGapsAndReport(aggregateType, LongRange.only(highest), List.of(), transientGaps);
    }

    /**
     * How long this handler keeps waiting for a transient gap's event before it gives up on it (promotes it to a permanent
     * gap), when that is a fixed duration it can state. A subscription that tracks gaps itself - the CDC event store's
     * delivery tracker - waits for a gap that long too, so it follows a customised promotion strategy rather than
     * assuming the default. The default implementation returns {@link Optional#empty()}: unknown (or no gap handling at
     * all), and the caller falls back to the default of 120 seconds.
     *
     * @return the time after which a transient gap is given up, or empty if this handler does not state one
     */
    default Optional<Duration> transientGapGiveUpThreshold() {
        return Optional.empty();
    }

    /**
     * Reset all transient gaps registered by this subscription handler for the given aggregate type
     *
     * @param aggregateType the aggregate type we want to reset transient gaps for
     * @return list of transient gap {@link GlobalEventOrder}'s the were removed
     */
    List<GlobalEventOrder> resetTransientGapsFor(AggregateType aggregateType);

    /**
     * Get all transient gaps registered by this subscription handler for the given aggregate type
     *
     * @param aggregateType the aggregate type we want to get all transient gaps for
     * @return list of transient gap {@link GlobalEventOrder}'s registered by this subscription handler for the given aggregate type
     */
    List<GlobalEventOrder> getTransientGapsFor(AggregateType aggregateType);

    /**
     * Get all permanent {@link AggregateType} specific event-stream gaps across all subscribers (shorthand for calling {@link EventStreamGapHandler#getPermanentGapsFor(AggregateType)})
     *
     * @param aggregateType the aggregate type we want all permanent gaps for
     * @return stream of permanent gap {@link GlobalEventOrder}'s registered for the given aggregate type
     */
    Stream<GlobalEventOrder> getPermanentGapsFor(AggregateType aggregateType);
}
