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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.reactive.*;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link EventStoreEventBus}, obtained from {@link EventStoreEventBus#builder()}.
 * <p>
 * The bus can either wrap an {@link EventBus} the caller already has — {@link #setEventBus(EventBus)} — or build an
 * internal {@link LocalEventBus} from the tuning values set here. The two are mutually exclusive; supplying both is
 * rejected in {@link #build()} rather than silently letting one win.
 * <p>
 * Every tuning value is held as a boxed nullable and is only applied to the {@link LocalEventBus.Builder} when it was
 * actually set, so an unset value keeps {@code LocalEventBus}' own default rather than a default restated here that
 * could drift from it.
 */
public final class EventStoreEventBusBuilder {
    private EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private EventBus                                                    eventBus;
    private OnErrorHandler                                              onErrorHandler;
    private Integer                                                     parallelThreads;
    private Integer                                                     eventBusBackpressureBufferSize;
    private Integer                                                     overflowMaxRetries;
    private Double                                                      queuedTaskCapFactor;

    /**
     * @param unitOfWorkFactory the {@link EventStoreUnitOfWorkFactory} coordinating the {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}
     *                          life cycle, such that {@link PersistedEvents} are published at all {@link CommitStage}s. Required
     * @return this builder instance for fluent chaining
     */
    public EventStoreEventBusBuilder setUnitOfWorkFactory(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return this;
    }

    /**
     * @param eventBus an existing {@link EventBus} to delegate to. When {@code null} (the default) an internal
     *                 {@link LocalEventBus} named {@code EventStoreLocalBus} is created from the tuning values below
     * @return this builder instance for fluent chaining
     */
    public EventStoreEventBusBuilder setEventBus(EventBus eventBus) {
        this.eventBus = eventBus;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setEventBus(EventBus)}.
     *
     * @param eventBus the bus to delegate to, or empty to create the internal {@link LocalEventBus}
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public EventStoreEventBusBuilder setEventBus(Optional<EventBus> eventBus) {
        requireNonNull(eventBus, "eventBus cannot be null");
        return setEventBus(eventBus.orElse(null));
    }

    /**
     * @param onErrorHandler the error handler called if a subscriber/consumer fails to handle an event. Only applies to
     *                       the internal {@link LocalEventBus}
     * @return this builder instance for fluent chaining
     */
    public EventStoreEventBusBuilder setOnErrorHandler(OnErrorHandler onErrorHandler) {
        this.onErrorHandler = onErrorHandler;
        return this;
    }

    /**
     * @param parallelThreads the number of parallel asynchronous processing threads. Only applies to the internal
     *                        {@link LocalEventBus}
     * @return this builder instance for fluent chaining
     */
    public EventStoreEventBusBuilder setParallelThreads(int parallelThreads) {
        this.parallelThreads = parallelThreads;
        return this;
    }

    /**
     * @param eventBusBackpressureBufferSize the back-pressure size for {@code Sinks.Many}'s onBackpressureBuffer. Only
     *                                       applies to the internal {@link LocalEventBus}
     * @return this builder instance for fluent chaining
     */
    public EventStoreEventBusBuilder setEventBusBackpressureBufferSize(int eventBusBackpressureBufferSize) {
        this.eventBusBackpressureBufferSize = eventBusBackpressureBufferSize;
        return this;
    }

    /**
     * @param overflowMaxRetries the maximum number of retries for events that overflow the Flux. Only applies to the
     *                           internal {@link LocalEventBus}
     * @return this builder instance for fluent chaining
     */
    public EventStoreEventBusBuilder setOverflowMaxRetries(int overflowMaxRetries) {
        this.overflowMaxRetries = overflowMaxRetries;
        return this;
    }

    /**
     * @param queuedTaskCapFactor the factor used to calculate queued task capacity. Only applies to the internal
     *                            {@link LocalEventBus}
     * @return this builder instance for fluent chaining
     */
    public EventStoreEventBusBuilder setQueuedTaskCapFactor(double queuedTaskCapFactor) {
        this.queuedTaskCapFactor = queuedTaskCapFactor;
        return this;
    }

    /**
     * Builds the bus.
     *
     * @return the bus
     * @throws IllegalArgumentException if an {@link EventBus} was supplied together with tuning values that only apply
     *                                  to the internal {@link LocalEventBus}
     */
    public EventStoreEventBus build() {
        requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null");
        if (eventBus != null) {
            var tuningWasSet = onErrorHandler != null
                    || parallelThreads != null
                    || eventBusBackpressureBufferSize != null
                    || overflowMaxRetries != null
                    || queuedTaskCapFactor != null;
            if (tuningWasSet) {
                throw new IllegalArgumentException("An eventBus was supplied together with LocalEventBus tuning values (onErrorHandler/parallelThreads/"
                                                           + "eventBusBackpressureBufferSize/overflowMaxRetries/queuedTaskCapFactor). Those only apply to the "
                                                           + "internal LocalEventBus, so setting both cannot be honoured — set one or the other.");
            }
            return new EventStoreEventBus(eventBus, unitOfWorkFactory);
        }

        var localEventBusBuilder = new LocalEventBus.Builder().busName("EventStoreLocalBus");
        if (parallelThreads != null) {
            localEventBusBuilder.parallelThreads(parallelThreads);
        }
        if (eventBusBackpressureBufferSize != null) {
            localEventBusBuilder.backpressureBufferSize(eventBusBackpressureBufferSize);
        }
        if (onErrorHandler != null) {
            localEventBusBuilder.onErrorHandler(onErrorHandler);
        }
        if (overflowMaxRetries != null) {
            localEventBusBuilder.overflowMaxRetries(overflowMaxRetries);
        }
        if (queuedTaskCapFactor != null) {
            localEventBusBuilder.queuedTaskCapFactor(queuedTaskCapFactor);
        }
        return new EventStoreEventBus(localEventBusBuilder.build(), unitOfWorkFactory);
    }
}
