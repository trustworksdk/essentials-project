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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.SubscriptionGapHandler;
import dk.trustworks.essentials.components.foundation.types.*;
import reactor.core.Disposable;
import org.slf4j.*;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The channel through which the subscriber of an {@link EventStore} poll reports each event it is done with - handled,
 * or given up on - so the event store resolves the transient gap a <b>gap fill</b> fills only then, rather than as soon
 * as it has handed the event on.
 * <p>
 * <b>Why:</b> a gap fill is an event whose transaction committed after events with a higher global order had already
 * been delivered. It reaches the subscriber after them, below its resume point, so the transient gap the subscriber's
 * {@link SubscriptionGapHandler} recorded for it is the only durable record that the event is still owed. Resolved
 * before the subscriber has handled the event, a stop or a crash in between loses it for good: the restarted
 * subscription resumes above it, and no longer has a gap to ask for it again. Handing an event on is not handling it
 * whenever the subscriber handles asynchronously - a batched subscriber collecting events for its next batch, a retry
 * of an I/O failure that waits for its backoff on another thread, or an event waiting in a {@link Flux#limitRate(int)}
 * queue in front of a subscriber that withholds demand.
 * <p>
 * <b>Opt-in:</b> pass an acknowledgement to
 * {@link EventStore#pollEvents(AggregateType, long, Optional, Optional, Optional, Optional, Optional, SubscriberAcknowledgement)}
 * or {@link EventStore#unboundedPollForEvents(AggregateType, long, Optional, Optional, Optional, Optional, SubscriberAcknowledgement)},
 * and {@link #acknowledge} every event the returned flux hands you once you are done with it. The overloads without
 * an acknowledgement keep resolving a gap fill's gap once the event was handed on. The subscription manager's
 * {@code PersistedEventSubscriber} and {@code BatchedPersistedEventSubscriber} opt in.
 * <p>
 * <b>Contract for the subscriber:</b>
 * <ul>
 *     <li>Acknowledge every event once it is handled, inside the unit of work that handled it if there is one - the event
 *     store then resolves the gap in that same unit of work, atomically with the handling, and forgets the event only
 *     once that unit of work committed. A rolled-back unit of work leaves the gap open.</li>
 *     <li>Acknowledge an event you give up on, too (skipped, or taken over by someone else): it is done with, and its gap
 *     must not keep asking for it.</li>
 *     <li>Do not acknowledge an event you have not handled because you stopped - it is owed to the next subscription.</li>
 *     <li>Acknowledging an event that is no gap fill costs nothing but a map lookup; only a gap fill's acknowledgement
 *     touches the database.</li>
 * </ul>
 * A gap fill that was handed on and not acknowledged yet is not handed on again by the same subscription, however
 * often a poll reads it while its gap is still open; a new subscription is handed it again.
 * <p>
 * <b>Contract for the event store:</b> an event store that honours the acknowledgement registers with
 * {@link #onAcknowledge(Consumer)} when the flux is subscribed. {@link #isHonoured()} then tells the subscriber that the
 * event store resolves gap fills on acknowledgement - an event store that does not override the overloads taking an
 * acknowledgement never registers, and resolves on hand-on as before. One acknowledgement serves one subscription:
 * create a new one for every subscription. Subscribing the returned flux again once the previous subscribe ended
 * ({@code retry()}, {@code repeat()}) is the same subscription: the event store replaces its registration, so
 * acknowledging a gap fill the previous subscribe handed on no longer resolves its gap - it stays open, and the next
 * subscribe hands the fill on again.
 *
 * @see EventStore#pollEvents(AggregateType, long, Optional, Optional, Optional, Optional, Optional, SubscriberAcknowledgement)
 * @see SubscriptionGapHandler#resolveFilledGaps(AggregateType, List)
 */
public final class SubscriberAcknowledgement {
    private static final Logger                        log      = LoggerFactory.getLogger(SubscriberAcknowledgement.class);
    private final List<Consumer<List<PersistedEvent>>> listeners = new CopyOnWriteArrayList<>();
    /**
     * The registrations not disposed yet - more than one means more than one subscription
     */
    private final AtomicInteger                        activeRegistrations           = new AtomicInteger();
    private final AtomicBoolean                        warnedAboutSecondRegistration = new AtomicBoolean();
    private volatile boolean                           honoured;

    private SubscriberAcknowledgement() {
    }

    /**
     * @return a new acknowledgement for one subscription, which no event store honours until one registers with it
     */
    public static SubscriberAcknowledgement create() {
        return new SubscriberAcknowledgement();
    }

    /**
     * The subscriber is done with {@code event}: it handled it, or gave up on it. Called by the subscriber, on the thread
     * - and inside the unit of work, if any - that handled the event. A no-op while no event store is registered.
     *
     * @param event the event the subscriber is done with
     * @throws RuntimeException whatever the event store failed with while resolving the event's gap inside the caller's
     *                          unit of work - the gap stays open, and the unit of work should not commit
     */
    public void acknowledge(PersistedEvent event) {
        requireNonNull(event, "No event provided");
        acknowledge(List.of(event));
    }

    /**
     * The subscriber is done with {@code events} - see {@link #acknowledge(PersistedEvent)}. A batched subscriber
     * acknowledges the batch it handled in one call.
     *
     * @param events the events the subscriber is done with
     * @throws RuntimeException whatever the event store failed with while resolving the events' gaps inside the caller's
     *                          unit of work - the gaps stay open, and the unit of work should not commit
     */
    public void acknowledge(List<PersistedEvent> events) {
        requireNonNull(events, "No events provided");
        if (events.isEmpty()) {
            return;
        }
        for (var listener : listeners) {
            listener.accept(events);
        }
    }

    /**
     * @return true once an event store registered with this acknowledgement ({@link #onAcknowledge(Consumer)}): it
     * resolves a gap fill's transient gap when the event is acknowledged, not when it is handed on. False for an event
     * store that does not support acknowledgement, which resolves on hand-on - a subscriber that handles asynchronously
     * then has to protect a gap fill it was handed and did not handle itself (e.g. by holding its resume point at it)
     */
    public boolean isHonoured() {
        return honoured;
    }

    /**
     * Called by the event store when the polling flux is subscribed: from then on, every acknowledgement is passed to
     * {@code listener}, on the acknowledging thread. Marks this acknowledgement {@link #isHonoured() honoured}.
     * <p>
     * Essentials' event stores do not dispose the registration when the subscription ends: an event whose handling
     * completes while the subscription is being stopped was handled, and acknowledging it should still resolve its gap,
     * in the unit of work that handled it - rather than leave it open and hand the event to the next subscription again.
     * The registration then lives as long as this acknowledgement, which belongs to that one subscription. Only when the
     * same polling flux is subscribed again - {@code retry()}, {@code repeat()} - does the event store dispose the
     * registration of the subscribe that ended, before it registers the next one.
     * <p>
     * A registration while another one is still active - not disposed - logs a one-time WARN: the acknowledgement is
     * used by more than one subscription.
     *
     * @param listener receives the events acknowledged; resolves the gaps of the gap fills among them
     * @return disposing it stops passing acknowledgements to {@code listener}; disposing it again does nothing
     */
    public Disposable onAcknowledge(Consumer<List<PersistedEvent>> listener) {
        requireNonNull(listener, "No listener provided");
        // Decided by the registration itself, so two concurrent registrations cannot both miss the other one
        if (activeRegistrations.incrementAndGet() > 1 && warnedAboutSecondRegistration.compareAndSet(false, true)) {
            log.warn("A second event store subscription registered with {} - one SubscriberAcknowledgement serves ONE subscription, so every "
                     + "acknowledgement is now passed to all of them and resolves gaps the other subscription's events never filled. Create a new "
                     + "SubscriberAcknowledgement for every subscription",
                     this);
        }
        listeners.add(listener);
        honoured = true;
        return new Registration(listener);
    }

    /**
     * One {@link #onAcknowledge(Consumer)} registration; disposed at most once
     */
    private final class Registration implements Disposable {
        private final Consumer<List<PersistedEvent>> listener;
        private final AtomicBoolean                  disposed = new AtomicBoolean();

        private Registration(Consumer<List<PersistedEvent>> listener) {
            this.listener = listener;
        }

        @Override
        public void dispose() {
            if (disposed.compareAndSet(false, true)) {
                listeners.remove(listener);
                activeRegistrations.decrementAndGet();
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }
    }

    @Override
    public String toString() {
        return "SubscriberAcknowledgement{honoured=" + honoured + ", listeners=" + listeners.size() + '}';
    }
}
