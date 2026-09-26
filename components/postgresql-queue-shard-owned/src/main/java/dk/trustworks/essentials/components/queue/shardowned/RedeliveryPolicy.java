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

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * How often a failed message is retried and how long the waits grow.
 *
 * @param maxAttempts total delivery attempts before the message is dead-lettered, counting the first
 * @param multiplier  1.0 gives a fixed backoff; above 1.0 gives exponential
 */
public record RedeliveryPolicy(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay) {

    public RedeliveryPolicy {
        requireTrue(maxAttempts >= 1, "maxAttempts must be at least 1");
        requireNonNull(initialDelay, "No initialDelay provided");
        requireNonNull(maxDelay, "No maxDelay provided");
        requireTrue(multiplier >= 1.0d, "multiplier must be at least 1.0");
    }

    public static RedeliveryPolicy fixed(Duration delay, int maxAttempts) {
        return new RedeliveryPolicy(maxAttempts, delay, 1.0d, delay);
    }

    public static RedeliveryPolicy exponential(Duration initialDelay, double multiplier, Duration maxDelay, int maxAttempts) {
        return new RedeliveryPolicy(maxAttempts, initialDelay, multiplier, maxDelay);
    }

    /**
     * @param attemptsSoFar deliveries already made, so the first retry passes 1
     */
    public Duration delayAfter(int attemptsSoFar) {
        var millis = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, attemptsSoFar - 1));
        return Duration.ofMillis((long) Math.min(millis, maxDelay.toMillis()));
    }

    public boolean isExhausted(int attemptsSoFar) {
        return attemptsSoFar >= maxAttempts;
    }
}
