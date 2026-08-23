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
import reactor.util.retry.*;

import java.time.Duration;
import java.util.function.BiConsumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link PersistedEventSubscriber}, obtained from {@link PersistedEventSubscriber#builder()}.
 * <p>
 * {@code forwardToEventHandlerRetryBackoffSpec} defaults to the same indefinite-retry-on-IO-exception spec the
 * shorthand constructors of the batched subscriber use, so a caller that does not care about retry tuning does not have
 * to restate it.
 */
public final class PersistedEventSubscriberBuilder {
    private PersistedEventHandler                 eventHandler;
    private EventStoreSubscription                eventStoreSubscription;
    private BiConsumer<PersistedEvent, Throwable> onErrorHandler;
    private RetryBackoffSpec                      forwardToEventHandlerRetryBackoffSpec = defaultRetryBackoffSpec();
    private long                                  eventStorePollingBatchSize;
    private EventStore                            eventStore;

    /**
     * Indefinite retries for exceptions where {@link IOExceptionUtil#isIOException(Throwable)} returns true.
     *
     * @return the default retry spec
     */
    static RetryBackoffSpec defaultRetryBackoffSpec() {
        return Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(100)) // Initial delay of 100ms
                    .maxBackoff(Duration.ofSeconds(1))               // Maximum backoff of 1 second
                    .jitter(0.5)
                    .filter(IOExceptionUtil::isIOException);
    }

    /**
     * @param eventHandler the handler that {@link PersistedEvent}s are forwarded to. Required
     * @return this builder instance for fluent chaining
     */
    public PersistedEventSubscriberBuilder setEventHandler(PersistedEventHandler eventHandler) {
        this.eventHandler = eventHandler;
        return this;
    }

    /**
     * @param eventStoreSubscription the subscription, as created by {@link EventStoreSubscriptionManager}. Must support
     *                               resume-points. Required
     * @return this builder instance for fluent chaining
     */
    public PersistedEventSubscriberBuilder setEventStoreSubscription(EventStoreSubscription eventStoreSubscription) {
        this.eventStoreSubscription = eventStoreSubscription;
        return this;
    }

    /**
     * @param onErrorHandler the handler called for non-retryable exceptions, as decided by the retry spec. Required
     * @return this builder instance for fluent chaining
     */
    public PersistedEventSubscriberBuilder setOnErrorHandler(BiConsumer<PersistedEvent, Throwable> onErrorHandler) {
        this.onErrorHandler = onErrorHandler;
        return this;
    }

    /**
     * @param forwardToEventHandlerRetryBackoffSpec the retry spec applied when forwarding to the event handler.
     *                                              Defaults to indefinite retries for IO exceptions
     * @return this builder instance for fluent chaining
     */
    public PersistedEventSubscriberBuilder setForwardToEventHandlerRetryBackoffSpec(RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec) {
        this.forwardToEventHandlerRetryBackoffSpec = forwardToEventHandlerRetryBackoffSpec;
        return this;
    }

    /**
     * @param eventStorePollingBatchSize the batch size used when polling events from the {@link EventStore}. Required
     * @return this builder instance for fluent chaining
     */
    public PersistedEventSubscriberBuilder setEventStorePollingBatchSize(long eventStorePollingBatchSize) {
        this.eventStorePollingBatchSize = eventStorePollingBatchSize;
        return this;
    }

    /**
     * @param eventStore the {@link EventStore} to use. Required
     * @return this builder instance for fluent chaining
     */
    public PersistedEventSubscriberBuilder setEventStore(EventStore eventStore) {
        this.eventStore = eventStore;
        return this;
    }

    /**
     * Builds the subscriber.
     *
     * @return the subscriber
     */
    @SuppressWarnings("removal")
    public PersistedEventSubscriber build() {
        return new PersistedEventSubscriber(requireNonNull(eventHandler, "eventHandler cannot be null"),
                                            requireNonNull(eventStoreSubscription, "eventStoreSubscription cannot be null"),
                                            requireNonNull(onErrorHandler, "onErrorHandler cannot be null"),
                                            requireNonNull(forwardToEventHandlerRetryBackoffSpec, "forwardToEventHandlerRetryBackoffSpec cannot be null"),
                                            eventStorePollingBatchSize,
                                            requireNonNull(eventStore, "eventStore cannot be null"));
    }
}
