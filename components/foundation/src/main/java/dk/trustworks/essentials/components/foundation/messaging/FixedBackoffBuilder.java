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
 * A builder for defining a {@link RedeliveryPolicy} with a Fixed Backoff strategy
 * @see RedeliveryPolicy#builder()
 * @see RedeliveryPolicy#exponentialBackoff()
 * @see RedeliveryPolicy#linearBackoff()
 * @see RedeliveryPolicy#fixedBackoff()
 */
public final class FixedBackoffBuilder {
    private Duration                    redeliveryDelay;
    private int                         maximumNumberOfRedeliveries;
    private boolean                     maximumNumberOfRedeliveriesSet;
    private MessageDeliveryErrorHandler deliveryErrorHandler = MessageDeliveryErrorHandler.alwaysRetry();


    public FixedBackoffBuilder setRedeliveryDelay(Duration redeliveryDelay) {
        this.redeliveryDelay = redeliveryDelay;
        return this;
    }

    public FixedBackoffBuilder setMaximumNumberOfRedeliveries(int maximumNumberOfRedeliveries) {
        this.maximumNumberOfRedeliveries = maximumNumberOfRedeliveries;
        this.maximumNumberOfRedeliveriesSet = true;
        return this;
    }

    public FixedBackoffBuilder setDeliveryErrorHandler(MessageDeliveryErrorHandler deliveryErrorHandler) {
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
        // The constructor, not RedeliveryPolicy.builder(): that builder runs the same checks and would WARN a second time
        return new RedeliveryPolicy(redeliveryDelay,
                                    redeliveryDelay,
                                    1.0d,
                                    redeliveryDelay,
                                    maximumNumberOfRedeliveries,
                                    deliveryErrorHandler);
    }

    RedeliveryPolicyBuilderChecks checks() {
        return new RedeliveryPolicyBuilderChecks(FixedBackoffBuilder.class)
                .delay(redeliveryDelay, "setRedeliveryDelay")
                .maximumNumberOfRedeliveries(maximumNumberOfRedeliveriesSet);
    }
}