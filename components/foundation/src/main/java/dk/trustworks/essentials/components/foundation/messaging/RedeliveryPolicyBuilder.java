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

public final class RedeliveryPolicyBuilder {
    private Duration                    initialRedeliveryDelay;
    private Duration                    followupRedeliveryDelay;
    private double                      followupRedeliveryDelayMultiplier;
    private Duration                    maximumFollowupRedeliveryDelayThreshold;
    private int                         maximumNumberOfRedeliveries;
    private boolean                     maximumNumberOfRedeliveriesSet;
    private MessageDeliveryErrorHandler deliveryErrorHandler = MessageDeliveryErrorHandler.alwaysRetry();

    public RedeliveryPolicyBuilder setInitialRedeliveryDelay(Duration initialRedeliveryDelay) {
        this.initialRedeliveryDelay = initialRedeliveryDelay;
        return this;
    }

    public RedeliveryPolicyBuilder setFollowupRedeliveryDelay(Duration followupRedeliveryDelay) {
        this.followupRedeliveryDelay = followupRedeliveryDelay;
        return this;
    }

    public RedeliveryPolicyBuilder setFollowupRedeliveryDelayMultiplier(double followupRedeliveryDelayMultiplier) {
        this.followupRedeliveryDelayMultiplier = followupRedeliveryDelayMultiplier;
        return this;
    }

    public RedeliveryPolicyBuilder setMaximumFollowupRedeliveryDelayThreshold(Duration maximumFollowupRedeliveryDelayThreshold) {
        this.maximumFollowupRedeliveryDelayThreshold = maximumFollowupRedeliveryDelayThreshold;
        return this;
    }

    public RedeliveryPolicyBuilder setMaximumNumberOfRedeliveries(int maximumNumberOfRedeliveries) {
        this.maximumNumberOfRedeliveries = maximumNumberOfRedeliveries;
        this.maximumNumberOfRedeliveriesSet = true;
        return this;
    }

    public RedeliveryPolicyBuilder setDeliveryErrorHandler(MessageDeliveryErrorHandler deliveryErrorHandler) {
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
        return new RedeliveryPolicyBuilderChecks(RedeliveryPolicyBuilder.class)
                .delay(initialRedeliveryDelay, "setInitialRedeliveryDelay")
                .delay(followupRedeliveryDelay, "setFollowupRedeliveryDelay")
                .multiplier(followupRedeliveryDelayMultiplier)
                .delay(maximumFollowupRedeliveryDelayThreshold, "setMaximumFollowupRedeliveryDelayThreshold")
                .maximumNumberOfRedeliveries(maximumNumberOfRedeliveriesSet);
    }
}