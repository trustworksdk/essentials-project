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
 * A builder for defining a {@link RedeliveryPolicy} with a Linear Backoff strategy: redelivery {@code n} (counting from 0)
 * waits {@code redeliveryDelay × (n+1)}, capped at {@code maximumFollowupRedeliveryDelayThreshold}
 *
 * @see RedeliveryPolicy#calculateNextRedeliveryDelay(int)
 * @see RedeliveryPolicy#builder()
 * @see RedeliveryPolicy#exponentialBackoff()
 * @see RedeliveryPolicy#linearBackoff()
 * @see RedeliveryPolicy#fixedBackoff()
 */
public final class LinearBackoffBuilder {
    private Duration                    redeliveryDelay;
    private Duration                    maximumFollowupRedeliveryDelayThreshold;
    private int                         maximumNumberOfRedeliveries;
    private boolean                     maximumNumberOfRedeliveriesSet;
    private MessageDeliveryErrorHandler deliveryErrorHandler = MessageDeliveryErrorHandler.alwaysRetry();


    public LinearBackoffBuilder setRedeliveryDelay(Duration redeliveryDelay) {
        this.redeliveryDelay = redeliveryDelay;
        return this;
    }

    public LinearBackoffBuilder setMaximumFollowupRedeliveryDelayThreshold(Duration maximumFollowupRedeliveryDelayThreshold) {
        this.maximumFollowupRedeliveryDelayThreshold = maximumFollowupRedeliveryDelayThreshold;
        return this;
    }

    public LinearBackoffBuilder setMaximumNumberOfRedeliveries(int maximumNumberOfRedeliveries) {
        this.maximumNumberOfRedeliveries = maximumNumberOfRedeliveries;
        this.maximumNumberOfRedeliveriesSet = true;
        return this;
    }

    public LinearBackoffBuilder setDeliveryErrorHandler(MessageDeliveryErrorHandler deliveryErrorHandler) {
        this.deliveryErrorHandler = deliveryErrorHandler;
        return this;
    }

    /**
     * Build the policy.
     * <p>
     * A setting that was never given is logged in one WARN naming the setter to call (QL-3): an unset delay (which
     * still fails, as before), {@code setMaximumNumberOfRedeliveries} not called (it silently means 0 redeliveries).
     * <b>From 0.61 these make {@code build()} throw an {@link IllegalArgumentException}</b>; in 0.60.x the policy is
     * built exactly as before.
     *
     * @return the policy
     */
    public RedeliveryPolicy build() {
        checks().warnIfIncomplete();
        return RedeliveryPolicy.linear(redeliveryDelay,
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
        return new RedeliveryPolicyBuilderChecks(LinearBackoffBuilder.class)
                .delay(redeliveryDelay, "setRedeliveryDelay")
                .delay(maximumFollowupRedeliveryDelayThreshold, "setMaximumFollowupRedeliveryDelayThreshold")
                .maximumNumberOfRedeliveries(maximumNumberOfRedeliveriesSet);
    }
}
