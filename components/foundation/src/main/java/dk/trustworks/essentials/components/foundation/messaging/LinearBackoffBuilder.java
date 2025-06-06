/*
 * Copyright 2021-2025 the original author or authors.
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
 * A builder for defining a {@link RedeliveryPolicy} with a Linear Backoff strategy
 * @see RedeliveryPolicy#builder()
 * @see RedeliveryPolicy#exponentialBackoff()
 * @see RedeliveryPolicy#linearBackoff()
 * @see RedeliveryPolicy#fixedBackoff()
 */
public final class LinearBackoffBuilder {
    private Duration                    redeliveryDelay;
    private Duration                    maximumFollowupRedeliveryDelayThreshold;
    private int                         maximumNumberOfRedeliveries;
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
        return this;
    }

    public LinearBackoffBuilder setDeliveryErrorHandler(MessageDeliveryErrorHandler deliveryErrorHandler) {
        this.deliveryErrorHandler = deliveryErrorHandler;
        return this;
    }

    public RedeliveryPolicy build() {
        return RedeliveryPolicy.builder().setInitialRedeliveryDelay(redeliveryDelay)
                               .setFollowupRedeliveryDelay(redeliveryDelay)
                               .setFollowupRedeliveryDelayMultiplier(1.0d)
                               .setMaximumFollowupRedeliveryDelayThreshold(maximumFollowupRedeliveryDelayThreshold)
                               .setMaximumNumberOfRedeliveries(maximumNumberOfRedeliveries)
                               .setDeliveryErrorHandler(deliveryErrorHandler)
                               .build();

    }
}