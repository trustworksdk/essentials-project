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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * <b>Internal - not part of the public API</b>, and may change in any release: public only so the polling event store
 * and the CDC event store, in two packages, can share it. Everything in the {@code internal} package is internal.
 * <p>
 * The gap fills one subscription handed on and its subscriber has not acknowledged yet (see
 * {@link SubscriberAcknowledgement}) - and the resolution of their gaps once it does. Used by every polling subscription
 * of {@link PostgresqlEventStore} that is acknowledged, and by the delivery gate of every subscription of the CDC event
 * store - one protocol, so a fix to it applies to both.
 * <p>
 * A poll registers a gap fill here <b>before</b> it hands it on (a synchronous subscriber acknowledges inside the hand-on),
 * leaves its gap out of every reconciliation while it is here, and does not hand it on again when a later poll reads it
 * once more - its gap is still open, so the gap query keeps asking for it. Its transient gap is resolved when the
 * subscriber acknowledges it: inside the subscriber's unit of work if there is one, atomically with the handling, and
 * forgotten here only once that unit of work committed (a rollback leaves both the gap and the entry, so a retry that
 * acknowledges again resolves it then). Without a unit of work - an event the subscriber skipped or handed off - the
 * gap is resolved in a unit of work of its own. Either way the outcome is reported through
 * {@link EventStoreSubscriptionObserver#gapReconciliationOutcome} once committed.
 * <p>
 * Thread-safety: the subscriber acknowledges on its own thread (a batch handler's, an I/O retry's), concurrently with
 * the poll, and both use the subscription's one {@link SubscriptionGapHandler}. Every call to it - the poll's and this
 * one's - holds the gap handler's monitor, never across a commit, so a gap handler need not be thread-safe. A holder of
 * the monitor never waits for a row the other one holds: the acknowledgement deletes only the gaps of fills that are
 * still here, which the poll neither resolves nor promotes.
 */
public final class GapFillsAwaitingAcknowledgement implements UnitOfWorkLifecycleCallback<GapFillsAwaitingAcknowledgement.Resolution> {
    private static final Logger log = LoggerFactory.getLogger(GapFillsAwaitingAcknowledgement.class);

    private final SubscriptionGapHandler                         gapHandler;
    private final AggregateType                                  aggregateType;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final EventStoreSubscriptionObserver                 eventStoreSubscriptionObserver;
    private final String                                         eventStreamLogName;
    /**
     * By global order
     */
    private final ConcurrentMap<Long, PersistedEvent>            awaiting = new ConcurrentHashMap<>();

    /**
     * The gaps an acknowledgement resolved inside a unit of work it did not start, until that unit of work ends
     */
    record Resolution(List<PersistedEvent> gapFills, GapReconciliation outcome) {
    }

    public GapFillsAwaitingAcknowledgement(SubscriptionGapHandler gapHandler,
                                           AggregateType aggregateType,
                                           EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                           EventStoreSubscriptionObserver eventStoreSubscriptionObserver,
                                           String eventStreamLogName) {
        this.gapHandler = requireNonNull(gapHandler, "No gapHandler provided");
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.eventStoreSubscriptionObserver = requireNonNull(eventStoreSubscriptionObserver, "No eventStoreSubscriptionObserver provided");
        this.eventStreamLogName = requireNonNull(eventStreamLogName, "No eventStreamLogName provided");
    }

    /**
     * @return true if {@code event} was handed on and is not acknowledged yet - a poll that reads it again must not hand
     * it on again, nor resolve its gap
     */
    public boolean isAwaiting(PersistedEvent event) {
        return !awaiting.isEmpty() && awaiting.containsKey(event.globalEventOrder().longValue());
    }

    /**
     * @return the gap fills handed on and not acknowledged yet - given to every reconciliation along with the events a
     * poll loaded, so the gap handler never promotes their gaps (it does not promote a gap whose event it is given)
     */
    public Collection<PersistedEvent> awaitingEvents() {
        return awaiting.isEmpty() ? List.of() : List.copyOf(awaiting.values());
    }

    /**
     * @return {@code events}, plus the gap fills awaiting acknowledgement that are not among them - for a reconciliation,
     * so it does not promote their gaps (see {@link #awaitingEvents()}). They lie below the reconciled range, so they are
     * no new gaps either
     */
    public List<PersistedEvent> withAwaitingEvents(List<PersistedEvent> events) {
        requireNonNull(events, "No events provided");
        if (awaiting.isEmpty()) {
            return events;
        }
        var given  = new HashSet<Long>();
        events.forEach(event -> given.add(event.globalEventOrder().longValue()));
        var result = new ArrayList<>(events);
        awaiting.forEach((globalOrder, gapFill) -> {
            if (!given.contains(globalOrder)) {
                result.add(gapFill);
            }
        });
        return result;
    }

    /**
     * Called by the poll right before it hands {@code gapFills} on
     */
    public void awaitAcknowledgement(List<PersistedEvent> gapFills) {
        gapFills.forEach(gapFill -> awaiting.put(gapFill.globalEventOrder().longValue(), gapFill));
    }

    /**
     * The {@link SubscriberAcknowledgement} listener: resolve the gaps of the gap fills among {@code events}. Costs a map
     * lookup for an event that is no gap fill.
     *
     * @throws RuntimeException a failure to resolve them inside the caller's unit of work - the gaps stay open
     */
    public void acknowledged(List<PersistedEvent> events) {
        if (awaiting.isEmpty()) {
            return;
        }
        var gapFills = events.stream()
                             .filter(this::isAwaiting)
                             .toList();
        if (gapFills.isEmpty()) {
            return;
        }
        var currentUnitOfWork = unitOfWorkFactory.getCurrentUnitOfWork();
        if (currentUnitOfWork.isPresent()) {
            // The subscriber's unit of work: resolved atomically with the handling, forgotten once it committed
            var outcome = resolveFilledGaps(gapFills);
            currentUnitOfWork.get().registerLifecycleCallbackForResource(new Resolution(gapFills, outcome), this);
            log.debug("[{}] Resolving the gaps of the acknowledged gap fill(s) {} in the subscriber's unit of work",
                      eventStreamLogName,
                      globalOrdersOf(gapFills));
            return;
        }
        try {
            var outcome = unitOfWorkFactory.withUnitOfWork(unitOfWork -> resolveFilledGaps(gapFills));
            resolved(gapFills, outcome);
        } catch (RuntimeException e) {
            log.warn(msg("[{}] Could not resolve the gaps of the acknowledged gap fill(s) {} - they stay transient gaps, so the next subscription delivers those events again",
                         eventStreamLogName,
                         globalOrdersOf(gapFills)),
                     e);
        }
    }

    private GapReconciliation resolveFilledGaps(List<PersistedEvent> gapFills) {
        synchronized (gapHandler) {
            return gapHandler.resolveFilledGaps(aggregateType, gapFills);
        }
    }

    private void resolved(List<PersistedEvent> gapFills, GapReconciliation outcome) {
        gapFills.forEach(gapFill -> awaiting.remove(gapFill.globalEventOrder().longValue()));
        log.debug("[{}] Resolved the gaps of the acknowledged gap fill(s) {}", eventStreamLogName, globalOrdersOf(gapFills));
        if (!outcome.isEmpty()) {
            eventStoreSubscriptionObserver.gapReconciliationOutcome(gapHandler.subscriberId(), aggregateType, outcome);
        }
    }

    @Override
    public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<Resolution> associatedResources) {
        return BeforeCommitProcessingStatus.COMPLETED;
    }

    @Override
    public void afterCommit(UnitOfWork unitOfWork, List<Resolution> associatedResources) {
        associatedResources.forEach(resolution -> resolved(resolution.gapFills(), resolution.outcome()));
    }

    @Override
    public void beforeRollback(UnitOfWork unitOfWork, List<Resolution> associatedResources, Throwable causeOfTheRollback) {
    }

    @Override
    public void afterRollback(UnitOfWork unitOfWork, List<Resolution> associatedResources, Throwable causeOfTheRollback) {
        log.debug("[{}] The unit of work that acknowledged the gap fill(s) {} rolled back - their gaps stay open",
                  eventStreamLogName,
                  associatedResources.stream().flatMap(resolution -> globalOrdersOf(resolution.gapFills()).stream()).toList());
    }

    /**
     * Nothing a rollback to a savepoint cannot undo: the gaps were deleted in the transaction, and the entries here are
     * dropped only after it committed
     */
    @Override
    public boolean hasPendingChanges(Resolution resource) {
        return false;
    }

    private static List<Long> globalOrdersOf(List<PersistedEvent> events) {
        return events.stream().map(event -> event.globalEventOrder().longValue()).toList();
    }

    @Override
    public String toString() {
        return "GapFillsAwaitingAcknowledgement{" + eventStreamLogName + ", awaiting=" + awaiting.keySet() + '}';
    }
}
