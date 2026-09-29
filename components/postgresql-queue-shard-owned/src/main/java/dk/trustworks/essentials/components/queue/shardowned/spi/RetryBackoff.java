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

package dk.trustworks.essentials.components.queue.shardowned.spi;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * How long a failed message waits before its next delivery.
 * <p>
 * A function rather than a fixed formula, so that a caller with its own backoff rule can hand it to the engine
 * unchanged. The {@code DurableQueues} adapter does exactly that: it delegates to the
 * {@code RedeliveryPolicy}'s own delay calculation, so a policy's exponential, linear and fixed strategies all
 * produce the same waits on this engine as on {@code postgresql-queue}. Mapping a policy onto a formula of this
 * engine's had silently dropped whatever the formula could not express.
 */
@FunctionalInterface
public interface RetryBackoff {

    /**
     * @param attemptsSoFar deliveries already made, so the first retry passes 1
     * @return the wait before the next delivery; never {@code null}
     */
    Duration delayAfter(int attemptsSoFar);

    /**
     * The same wait before every retry.
     */
    static RetryBackoff fixed(Duration delay) {
        return new Exponential(delay, 1.0d, delay);
    }

    /**
     * {@code initialDelay × multiplier^(attemptsSoFar-1)}, capped at {@code maxDelay}.
     *
     * @param multiplier 1.0 gives a fixed backoff; above 1.0 gives exponential
     */
    static RetryBackoff exponential(Duration initialDelay, double multiplier, Duration maxDelay) {
        return new Exponential(initialDelay, multiplier, maxDelay);
    }

    /**
     * The engine's own formula, as a value so that two equal settings compare equal.
     */
    record Exponential(Duration initialDelay, double multiplier, Duration maxDelay) implements RetryBackoff {

        public Exponential {
            requireNonNull(initialDelay, "No initialDelay provided");
            requireNonNull(maxDelay, "No maxDelay provided");
            requireTrue(multiplier >= 1.0d, "multiplier must be at least 1.0");
        }

        @Override
        public Duration delayAfter(int attemptsSoFar) {
            var millis = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, attemptsSoFar - 1));
            return Duration.ofMillis((long) Math.min(millis, maxDelay.toMillis()));
        }
    }
}
