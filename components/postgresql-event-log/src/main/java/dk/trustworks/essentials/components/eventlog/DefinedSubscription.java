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
package dk.trustworks.essentials.components.eventlog;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The public face of a {@link Subscription} started from a {@link SubscriptionDefinition} (design phase 8 §2.5, decision 12)
 */
final class DefinedSubscription implements SubscriptionHandle {
    private final SubscriptionDefinition definition;
    private final Subscription           subscription;

    DefinedSubscription(SubscriptionDefinition definition, Subscription subscription) {
        this.definition = requireNonNull(definition, "No definition provided");
        this.subscription = requireNonNull(subscription, "No subscription provided");
    }

    /** @return the subscription behind this handle; for tests */
    Subscription subscription() {
        return subscription;
    }

    @Override
    public SubscriptionDefinition definition() {
        return definition;
    }

    @Override
    public void stop() {
        subscription.stop();
    }

    @Override
    public boolean isRunning() {
        return subscription.isRunning();
    }

    @Override
    public SubscriptionStatus status() {
        var resumePoint = subscription.lastSaved();
        var type        = subscription.activeType();
        var state       = type == null ? null : type.state();
        var lag         = state == null ? OptionalLong.empty() : OptionalLong.of(Math.max(0, state.cursor() - (resumePoint - 1)));
        var lease       = subscription.lease();
        var fence       = subscription.ownedFence();
        var error       = subscription.lastError();
        return new SubscriptionStatus(definition, subscription.model().name(), subscription.phase().name(), resumePoint, subscription.receivedWatermark(), lag,
                                      lease == null ? Optional.empty() : Optional.of(lease.state().name()),
                                      fence == null ? OptionalLong.empty() : OptionalLong.of(fence.fence()), subscription.heldAggregates(),
                                      error == null ? Optional.empty() : Optional.of(error.getClass().getName() + ": " + error.getMessage()));
    }

    @Override
    public CompletableFuture<Boolean> retryParked(long headGlobalOrder) {
        return subscription.retryParked(headGlobalOrder);
    }

    @Override
    public CompletableFuture<Boolean> discardParked(long headGlobalOrder) {
        return subscription.discardParked(headGlobalOrder);
    }

    @Override
    public String toString() {
        return "SubscriptionHandle[" + definition.subscriber() + " on " + definition.aggregateType() + ", " + definition.ownership() + "]";
    }
}
