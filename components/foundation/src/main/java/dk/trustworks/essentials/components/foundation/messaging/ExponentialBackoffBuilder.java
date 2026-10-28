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

package dk.trustworks.essentials.components.foundation.messaging;

import java.time.Duration;

/**
 * Builder for {@link RedeliveryPolicy} that allows for defining
 * an Exponential Backoff strategy - similar to {@link RedeliveryPolicyBuilder}.<br>
 * The first redelivery waits {@code initialRedeliveryDelay}; redelivery {@code n >= 1} waits
 * {@code followupRedeliveryDelay × followupRedeliveryDelayMultiplier^(n-1)}, capped at {@code maximumFollowupRedeliveryDelayThreshold}.
 * {@code (500ms, 500ms, 2.0, 1min)} waits 500ms, 500ms, 1s, 2s, 4s … up to 1min.
 * A multiplier left unset (or below {@code 1.0}) means no growth.
 * <p>
 * Every setter except {@link #setDeliveryErrorHandler(MessageDeliveryErrorHandler)} is required: {@link #build()} WARNs,
 * naming the setter, for each one left out or out of range - and from 0.61 will throw instead.
 *
 * @see RedeliveryPolicy#calculateNextRedeliveryDelay(int)
 * @see RedeliveryPolicy#builder()
 * @see RedeliveryPolicy#exponentialBackoff()
 * @see RedeliveryPolicy#linearBackoff()
 * @see RedeliveryPolicy#fixedBackoff()
 */
public final class ExponentialBackoffBuilder {
    private Duration                    initialRedeliveryDelay;
    private Duration                    followupRedeliveryDelay;
    private double                      followupRedeliveryDelayMultiplier;
    private Duration                    maximumFollowupRedeliveryDelayThreshold;
    private int                         maximumNumberOfRedeliveries;
    private boolean                     maximumNumberOfRedeliveriesSet;
    private MessageDeliveryErrorHandler deliveryErrorHandler = MessageDeliveryErrorHandler.alwaysRetry();

    public ExponentialBackoffBuilder setInitialRedeliveryDelay(Duration initialRedeliveryDelay) {
        this.initialRedeliveryDelay = initialRedeliveryDelay;
        return this;
    }

    public ExponentialBackoffBuilder setFollowupRedeliveryDelay(Duration followupRedeliveryDelay) {
        this.followupRedeliveryDelay = followupRedeliveryDelay;
        return this;
    }

    public ExponentialBackoffBuilder setFollowupRedeliveryDelayMultiplier(double followupRedeliveryDelayMultiplier) {
        this.followupRedeliveryDelayMultiplier = followupRedeliveryDelayMultiplier;
        return this;
    }

    public ExponentialBackoffBuilder setMaximumFollowupRedeliveryDelayThreshold(Duration maximumFollowupRedeliveryDelayThreshold) {
        this.maximumFollowupRedeliveryDelayThreshold = maximumFollowupRedeliveryDelayThreshold;
        return this;
    }

    public ExponentialBackoffBuilder setMaximumNumberOfRedeliveries(int maximumNumberOfRedeliveries) {
        this.maximumNumberOfRedeliveries = maximumNumberOfRedeliveries;
        this.maximumNumberOfRedeliveriesSet = true;
        return this;
    }

    public ExponentialBackoffBuilder setDeliveryErrorHandler(MessageDeliveryErrorHandler deliveryErrorHandler) {
        this.deliveryErrorHandler = deliveryErrorHandler;
        return this;
    }

    /**
     * Build the policy.
     * <p>
     * A setting that was never given is logged in one WARN naming the setter to call (QL-3): an unset delay (which
     * still fails, as before), {@code setMaximumNumberOfRedeliveries} not called (it silently means 0 redeliveries), and a multiplier below 1.0 or not set (it silently means no growth).
     * <b>From 0.61 these make {@code build()} throw an {@link IllegalArgumentException}</b>; in 0.60.x the policy is
     * built exactly as before.
     *
     * @return the policy
     */
    public RedeliveryPolicy build() {
        checks().warnIfIncomplete();
        // The constructor, not RedeliveryPolicy.builder(): that builder runs the same checks and would WARN a second time
        return new RedeliveryPolicy(initialRedeliveryDelay,
                                    followupRedeliveryDelay,
                                    followupRedeliveryDelayMultiplier,
                                    maximumFollowupRedeliveryDelayThreshold,
                                    maximumNumberOfRedeliveries,
                                    deliveryErrorHandler);
    }

    /**
     * The checks {@link #build()} runs, applied to this builder's current settings.
     * <p>
     * Package-private rather than private only so {@code RedeliveryPolicyBuilderChecksTest} can assert the problems
     * found without capturing the WARN {@link #build()} logs; nothing outside this package calls it.
     *
     * @return the checks, already run against this builder's current settings
     */
    RedeliveryPolicyBuilderChecks checks() {
        return new RedeliveryPolicyBuilderChecks(ExponentialBackoffBuilder.class)
                .delay(initialRedeliveryDelay, "setInitialRedeliveryDelay")
                .delay(followupRedeliveryDelay, "setFollowupRedeliveryDelay")
                .multiplier(followupRedeliveryDelayMultiplier)
                .delay(maximumFollowupRedeliveryDelayThreshold, "setMaximumFollowupRedeliveryDelayThreshold")
                .maximumNumberOfRedeliveries(maximumNumberOfRedeliveriesSet);
    }
}