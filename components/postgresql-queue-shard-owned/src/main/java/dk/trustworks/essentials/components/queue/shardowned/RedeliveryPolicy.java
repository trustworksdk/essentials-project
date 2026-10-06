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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * How often a failed message is retried and how long the waits are.
 *
 * @param maxAttempts total delivery attempts before the message is dead-lettered, counting the first
 * @param backoff     the wait before each retry
 */
public record RedeliveryPolicy(int maxAttempts, RetryBackoff backoff) {

    public RedeliveryPolicy {
        requireTrue(maxAttempts >= 1, "maxAttempts must be at least 1");
        requireNonNull(backoff, "No backoff provided");
    }

    /**
     * The policy a {@link MessageQueue#consume(MessageHandler, ConsumerOptions) subscription} runs under
     */
    public static RedeliveryPolicy from(ConsumerOptions options) {
        requireNonNull(options, "No options provided");
        return new RedeliveryPolicy(options.maxAttempts(), options.retryBackoff());
    }

    public static RedeliveryPolicy fixed(Duration delay, int maxAttempts) {
        return new RedeliveryPolicy(maxAttempts, RetryBackoff.fixed(delay));
    }

    /**
     * @param multiplier 1.0 gives a fixed backoff; above 1.0 gives exponential
     */
    public static RedeliveryPolicy exponential(Duration initialDelay, double multiplier, Duration maxDelay, int maxAttempts) {
        return new RedeliveryPolicy(maxAttempts, RetryBackoff.exponential(initialDelay, multiplier, maxDelay));
    }

    /**
     * @param attemptsSoFar deliveries already made, so the first retry passes 1
     * @return the backoff's answer; a caller-supplied backoff that answers negative is treated as no wait
     */
    public Duration delayAfter(int attemptsSoFar) {
        var delay = requireNonNull(backoff.delayAfter(attemptsSoFar), "The retry backoff returned no delay");
        return delay.isNegative() ? Duration.ZERO : delay;
    }

    public boolean isExhausted(int attemptsSoFar) {
        return attemptsSoFar >= maxAttempts;
    }
}
