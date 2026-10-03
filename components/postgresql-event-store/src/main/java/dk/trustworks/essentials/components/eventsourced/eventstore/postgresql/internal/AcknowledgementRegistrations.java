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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * <b>Internal - not part of the public API</b>, and may change in any release: public only so the polling event store
 * and the CDC event store, in two packages, can share it.
 * <p>
 * The registrations of one acknowledged event flux with the subscriber's {@link SubscriberAcknowledgement} - one per
 * subscribe of the flux, made before anything is handed on, so the subscriber can acknowledge whatever it is handed.
 * Used by {@link PostgresqlEventStore}'s acknowledged polls and by the CDC event store's - one protocol, so a fix to it
 * applies to both.
 * <p>
 * A registration is not disposed when its subscribe ends: an event whose handling completes while the subscription is
 * being stopped was handled, and acknowledging it still resolves its gap in the unit of work that handled it - instead
 * of leaving it open, which would hand the event to the next subscription again. Subscribing the flux again once the
 * previous subscribe ended - {@code retry()}, {@code repeat()} - disposes the previous subscribe's registration first,
 * though: it is the same subscription, and its registration would otherwise stay for the acknowledgement's lifetime,
 * pass every later acknowledgement to state that is done, and make the acknowledgement warn about a second
 * subscription. A gap fill the previous subscribe handed on and that was not acknowledged before then keeps its gap, so
 * the next subscribe hands it on again. A registration whose subscribe has not ended is left alone: subscribing the
 * flux twice at once is two subscriptions on one acknowledgement, and warns.
 */
public final class AcknowledgementRegistrations {
    private final SubscriberAcknowledgement  acknowledgement;
    private final AtomicReference<Subscribe> latestSubscribe = new AtomicReference<>();

    /**
     * What one subscribe of the flux registers, and the events it hands on
     *
     * @param listener the {@link SubscriberAcknowledgement#onAcknowledge} listener of this subscribe
     * @param events   the events this subscribe hands on
     */
    public record PerSubscribe(Consumer<List<PersistedEvent>> listener, Flux<PersistedEvent> events) {
        public PerSubscribe {
            requireNonNull(listener, "No listener provided");
            requireNonNull(events, "No events provided");
        }
    }

    /**
     * @param acknowledgement the subscriber's acknowledgement - one per flux
     */
    public AcknowledgementRegistrations(SubscriberAcknowledgement acknowledgement) {
        this.acknowledgement = requireNonNull(acknowledgement, "No acknowledgement provided");
    }

    /**
     * @param perSubscribe called on every subscribe, after the previous subscribe's registration was disposed (if it
     *                     ended): the listener to register and the events to hand on
     * @return the flux that registers on every subscribe
     */
    public Flux<PersistedEvent> registeredOnEverySubscribe(Supplier<PerSubscribe> perSubscribe) {
        requireNonNull(perSubscribe, "No perSubscribe provided");
        return Flux.defer(() -> {
            var previousSubscribe = latestSubscribe.get();
            if (previousSubscribe != null && previousSubscribe.hasEnded()) {
                previousSubscribe.registration().dispose();
            }
            var thisSubscribe = perSubscribe.get();
            var subscribe     = new Subscribe(acknowledgement.onAcknowledge(thisSubscribe.listener()));
            latestSubscribe.set(subscribe);
            // Marked before the signal reaches the subscriber: a retry() or repeat() subscribes again from within it
            return thisSubscribe.events()
                                .doOnTerminate(subscribe::ended)
                                .doOnCancel(subscribe::ended);
        });
    }

    /**
     * One subscribe: its registration with the acknowledgement, and whether it ended
     */
    private static final class Subscribe {
        private final    Disposable registration;
        private volatile boolean    ended;

        private Subscribe(Disposable registration) {
            this.registration = registration;
        }

        Disposable registration() {
            return registration;
        }

        void ended() {
            ended = true;
        }

        boolean hasEnded() {
            return ended;
        }
    }
}
