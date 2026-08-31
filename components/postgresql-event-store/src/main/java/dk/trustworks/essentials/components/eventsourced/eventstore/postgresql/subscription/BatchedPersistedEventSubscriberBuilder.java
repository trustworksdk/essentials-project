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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.function.BiConsumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link BatchedPersistedEventSubscriber}, obtained from {@link BatchedPersistedEventSubscriber#builder()}.
 * <p>
 * {@code forwardToEventHandlerRetryBackoffSpec} defaults to indefinite retries for exceptions where
 * {@link IOExceptionUtil#isIOException(Throwable)} returns true — the same spec the shorthand constructor applied — so
 * leaving it unset reproduces that constructor exactly.
 */
public final class BatchedPersistedEventSubscriberBuilder {
    private BatchedPersistedEventHandler          eventHandler;
    private EventStoreSubscription                eventStoreSubscription;
    private BiConsumer<PersistedEvent, Throwable> onErrorHandler;
    private RetryBackoffSpec                      forwardToEventHandlerRetryBackoffSpec = PersistedEventSubscriberBuilder.defaultRetryBackoffSpec();
    private long                                  eventStorePollingBatchSize;
    private EventStore                            eventStore;
    private int                                   maxBatchSize;
    private Duration                              maxLatency;

    /**
     * @param eventHandler the handler that batches of {@link PersistedEvent}s are forwarded to. Required
     * @return this builder instance for fluent chaining
     */
    public BatchedPersistedEventSubscriberBuilder setEventHandler(BatchedPersistedEventHandler eventHandler) {
        this.eventHandler = eventHandler;
        return this;
    }

    /**
     * @param eventStoreSubscription the subscription, as created by {@link EventStoreSubscriptionManager}. Required
     * @return this builder instance for fluent chaining
     */
    public BatchedPersistedEventSubscriberBuilder setEventStoreSubscription(EventStoreSubscription eventStoreSubscription) {
        this.eventStoreSubscription = eventStoreSubscription;
        return this;
    }

    /**
     * @param onErrorHandler the handler called for non-retryable exceptions, as decided by the retry spec. Required
     * @return this builder instance for fluent chaining
     */
    public BatchedPersistedEventSubscriberBuilder setOnErrorHandler(BiConsumer<PersistedEvent, Throwable> onErrorHandler) {
        this.onErrorHandler = onErrorHandler;
        return this;
    }

    /**
     * @param forwardToEventHandlerRetryBackoffSpec the retry spec applied when forwarding to the event handler.
     *                                              Defaults to indefinite retries for IO exceptions
     * @return this builder instance for fluent chaining
     */
    public BatchedPersistedEventSubscriberBuilder setForwardToEventHandlerRetryBackoffSpec(RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec) {
        this.forwardToEventHandlerRetryBackoffSpec = forwardToEventHandlerRetryBackoffSpec;
        return this;
    }

    /**
     * @param eventStorePollingBatchSize the batch size used when polling events from the {@link EventStore}. Required
     * @return this builder instance for fluent chaining
     */
    public BatchedPersistedEventSubscriberBuilder setEventStorePollingBatchSize(long eventStorePollingBatchSize) {
        this.eventStorePollingBatchSize = eventStorePollingBatchSize;
        return this;
    }

    /**
     * @param eventStore the {@link EventStore} to use. Required
     * @return this builder instance for fluent chaining
     */
    public BatchedPersistedEventSubscriberBuilder setEventStore(EventStore eventStore) {
        this.eventStore = eventStore;
        return this;
    }

    /**
     * @param maxBatchSize the maximum number of events to include in a batch before processing. Required
     * @return this builder instance for fluent chaining
     */
    public BatchedPersistedEventSubscriberBuilder setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
        return this;
    }

    /**
     * @param maxLatency the maximum time to wait before processing a partial batch. Required
     * @return this builder instance for fluent chaining
     */
    public BatchedPersistedEventSubscriberBuilder setMaxLatency(Duration maxLatency) {
        this.maxLatency = maxLatency;
        return this;
    }

    /**
     * Builds the subscriber.
     *
     * @return the subscriber
     */
    @SuppressWarnings("removal")
    public BatchedPersistedEventSubscriber build() {
        return new BatchedPersistedEventSubscriber(requireNonNull(eventHandler, "eventHandler cannot be null"),
                                                   requireNonNull(eventStoreSubscription, "eventStoreSubscription cannot be null"),
                                                   requireNonNull(onErrorHandler, "onErrorHandler cannot be null"),
                                                   requireNonNull(forwardToEventHandlerRetryBackoffSpec, "forwardToEventHandlerRetryBackoffSpec cannot be null"),
                                                   eventStorePollingBatchSize,
                                                   requireNonNull(eventStore, "eventStore cannot be null"),
                                                   maxBatchSize,
                                                   requireNonNull(maxLatency, "maxLatency cannot be null"));
    }
}
