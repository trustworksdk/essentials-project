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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Thrown by a {@link MessageHandler} to ask for the message to be delivered again after {@link #delay()}, without
 * that being a failure.
 * <p>
 * A handler has only two outcomes otherwise - return (acknowledge) or throw (fail) - and a failure runs through the
 * redelivery policy: its backoff replaces the delay the handler asked for, and once the policy is exhausted the
 * message is dead-lettered, so a handler that only wanted to wait could park a healthy message. A redelivery request
 * is scheduled at the requested delay and is not an attempt: the stored attempt count is left as it is, so a request
 * neither uses up the redelivery policy's budget for a later failure nor brings the message closer to being
 * dead-lettered by unclean takeovers. However often a handler asks, the request itself never dead-letters the message.
 * <p>
 * This differs from a manual redelivery in the classic {@code DurableQueues}, which counts as a redelivery. It used
 * to count here too, and a message whose handler had asked often enough was dead-lettered by the next unclean
 * takeover of its shard with no failure recorded.
 */
public class RedeliveryRequested extends RuntimeException {
    private final Duration delay;

    /**
     * @param delay how long to wait before the message is delivered again
     */
    public RedeliveryRequested(Duration delay) {
        this(delay, "Redelivery requested in " + delay);
    }

    /**
     * @param delay   how long to wait before the message is delivered again
     * @param message the exception message
     */
    public RedeliveryRequested(Duration delay, String message) {
        super(message, null, false, false);
        this.delay = requireNonNull(delay, "No delay provided");
    }

    /**
     * @return how long to wait before the message is delivered again
     */
    public Duration delay() {
        return delay;
    }
}
