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


import java.time.Duration;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Represents settings for managing EventStore subscriptions.
 * This configuration allows customization of parameters related to how
 * events are polled and how subscription state is maintained during event streaming.
 *
 * @param eventStorePollingBatchSize Specifies the number of events to retrieve in each batch when polling the EventStore.
 * @param eventStorePollingInterval Determines the interval between successive polling attempts to fetch events from the EventStore.
 * @param snapshotResumePointsEvery Specifies the duration after which the subscription's resume points are periodically saved to ensure that
 *                                  a subscription can resume from the last processed event in case of interruptions.
 * @param subscriptionErrorPolicy   What the asynchronous subscriptions do when their handler throws a non-I/O exception - see {@link SubscriptionErrorPolicy}
 */
public record EventStoreSubscriptionManagerSettings(int eventStorePollingBatchSize,
                                                    Duration eventStorePollingInterval,
                                                    Duration snapshotResumePointsEvery,
                                                    SubscriptionErrorPolicy subscriptionErrorPolicy) {

    public EventStoreSubscriptionManagerSettings {
        requireNonNull(subscriptionErrorPolicy, "No subscriptionErrorPolicy provided");
    }

    /**
     * Settings with the {@link SubscriptionErrorPolicy#defaultPolicy()} - the shape these settings had before the policy
     * was added
     *
     * @param eventStorePollingBatchSize Specifies the number of events to retrieve in each batch when polling the EventStore.
     * @param eventStorePollingInterval  Determines the interval between successive polling attempts to fetch events from the EventStore.
     * @param snapshotResumePointsEvery  Specifies the duration after which the subscription's resume points are periodically saved
     */
    public EventStoreSubscriptionManagerSettings(int eventStorePollingBatchSize,
                                                 Duration eventStorePollingInterval,
                                                 Duration snapshotResumePointsEvery) {
        this(eventStorePollingBatchSize, eventStorePollingInterval, snapshotResumePointsEvery, SubscriptionErrorPolicy.defaultPolicy());
    }

    /**
     * The {@link SubscriptionErrorPolicy} an asynchronous subscription of <code>eventHandler</code> applies: the handler's
     * own {@link PersistedEventHandler#subscriptionErrorPolicy()} if it returns one, otherwise {@link #subscriptionErrorPolicy()}
     *
     * @param eventHandler the subscription's event handler
     * @return the policy the subscription applies
     */
    public SubscriptionErrorPolicy subscriptionErrorPolicyFor(PersistedEventHandler eventHandler) {
        requireNonNull(eventHandler, "No eventHandler provided");
        return effectivePolicy(eventHandler.subscriptionErrorPolicy(), eventHandler);
    }

    /**
     * The {@link SubscriptionErrorPolicy} a batched subscription of <code>eventHandler</code> applies: the handler's own
     * {@link BatchedPersistedEventHandler#subscriptionErrorPolicy()} if it returns one, otherwise {@link #subscriptionErrorPolicy()}
     *
     * @param eventHandler the subscription's event handler
     * @return the policy the subscription applies
     */
    public SubscriptionErrorPolicy subscriptionErrorPolicyFor(BatchedPersistedEventHandler eventHandler) {
        requireNonNull(eventHandler, "No eventHandler provided");
        return effectivePolicy(eventHandler.subscriptionErrorPolicy(), eventHandler);
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private SubscriptionErrorPolicy effectivePolicy(Optional<SubscriptionErrorPolicy> handlerPolicy, Object eventHandler) {
        return requireNonNull(handlerPolicy,
                              msg("subscriptionErrorPolicy() of event handler '{}' returned null - return Optional.empty() to use the EventStoreSubscriptionManager's policy",
                                  eventHandler))
                .orElse(subscriptionErrorPolicy);
    }
}
