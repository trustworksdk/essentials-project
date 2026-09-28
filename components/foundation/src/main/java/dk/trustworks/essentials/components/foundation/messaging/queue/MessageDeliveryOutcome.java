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

package dk.trustworks.essentials.components.foundation.messaging.queue;

/**
 * What a {@link DurableQueueConsumer} should do with a message whose handler threw, as decided by
 * {@link MessageDeliveryClassifier}.
 * <p>
 * The two dead-lettering outcomes are kept apart rather than collapsed into a boolean because they mean
 * different things to an operator: {@link #PERMANENT_ERROR} is a message that was never going to succeed, while
 * {@link #REDELIVERIES_EXHAUSTED} is one that was retried the configured number of times and kept failing.
 */
public enum MessageDeliveryOutcome {
    /**
     * The failure was classified as permanent, so the message is dead-lettered without consuming any of its
     * remaining delivery attempts.
     */
    PERMANENT_ERROR,

    /**
     * The failure was retryable, but the message has already used its
     * {@link dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy#maximumNumberOfRedeliveries}
     * attempts, so it is dead-lettered.
     */
    REDELIVERIES_EXHAUSTED,

    /**
     * The failure was retryable and delivery attempts remain, so the message is redelivered according to the
     * {@link dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy}.
     */
    RETRY;

    /**
     * @return true if the message should be marked as a Poison-Message/Dead-Letter-Message
     */
    public boolean isDeadLetter() {
        return this != RETRY;
    }
}
