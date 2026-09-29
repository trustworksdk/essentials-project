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

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;

import java.time.Duration;
import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * In case the message delivery, handled by the {@link DurableQueueConsumer}, experiences an error/exception,
 * then the {@link RedeliveryPolicy} determines, with the aid of the {@link MessageDeliveryErrorHandler} and the provided
 * delivery settings, IF a Message should be retried ({@link DurableQueues#retryMessage(RetryMessage)}
 * or if it's going to be marked as a Poison-Message/Dead-Letter-Message ({@link DurableQueues#markAsDeadLetterMessage(MarkAsDeadLetterMessage)})
 *
 * @see RedeliveryPolicy#builder()
 * @see RedeliveryPolicy#exponentialBackoff()
 * @see RedeliveryPolicy#linearBackoff()
 * @see RedeliveryPolicy#fixedBackoff()
 */
public final class RedeliveryPolicy {
    /**
     * The delay after the first failed delivery (redelivery attempt {@code n = 0}). Not subject to {@link #maximumFollowupRedeliveryThreshold}
     */
    public final Duration                    initialRedeliveryDelay;
    /**
     * The delay after the second failed delivery ({@code n = 1}), and the base that {@link #followupRedeliveryDelayMultiplier} grows from after that.
     * For a {@link #linearBackoff(Duration, Duration, int)} policy: the amount each redelivery adds to the one before it
     */
    public final Duration                    followupRedeliveryDelay;
    /**
     * The factor each follow-up delay is multiplied by compared to the one before it: the delay for {@code n >= 1} is
     * {@code followupRedeliveryDelay × followupRedeliveryDelayMultiplier^(n-1)}. {@code 1.0} (or less) gives a constant follow-up delay.
     * Not used by a {@link #linearBackoff(Duration, Duration, int)} policy, where it is {@code 1.0}
     *
     * @see #calculateNextRedeliveryDelay(int)
     */
    public final double                      followupRedeliveryDelayMultiplier;
    /**
     * The cap on every follow-up delay ({@code n >= 1})
     */
    public final Duration                    maximumFollowupRedeliveryThreshold;
    public final int                         maximumNumberOfRedeliveries;
    public final MessageDeliveryErrorHandler deliveryErrorHandler;
    /**
     * Set only by {@link #linearBackoff(Duration, Duration, int)} and {@link LinearBackoffBuilder}: the delay for {@code n >= 1}
     * is {@code initialRedeliveryDelay + followupRedeliveryDelay × n} instead of the multiplier-based formula.
     * A policy's public fields cannot tell linear growth apart from an exponential policy with a multiplier of {@code 1.0}
     */
    private final boolean                    linearFollowupGrowth;

    /**
     * Create a generic builder for defining a {@link RedeliveryPolicy}
     *
     * @return a generic builder for defining a {@link RedeliveryPolicy}
     */
    public static RedeliveryPolicyBuilder builder() {
        return new RedeliveryPolicyBuilder();
    }

    /**
     * Create a builder for defining a {@link RedeliveryPolicy} that allows for defining
     * an Exponential Backoff strategy: the first redelivery waits {@code initialRedeliveryDelay}, redelivery {@code n >= 1} waits
     * {@code followupRedeliveryDelay × followupRedeliveryDelayMultiplier^(n-1)}, capped at {@code maximumFollowupRedeliveryDelayThreshold}.
     * See {@link #calculateNextRedeliveryDelay(int)}
     *
     * @return a builder for defining a {@link RedeliveryPolicy} that allows for defining
     * an Exponential Backoff strategy
     */
    public static ExponentialBackoffBuilder exponentialBackoff() {
        return new ExponentialBackoffBuilder();
    }

    /**
     * Create a builder for defining a {@link RedeliveryPolicy} with a Linear Backoff strategy: redelivery {@code n} (counting from 0)
     * waits {@code redeliveryDelay × (n+1)}, capped at {@code maximumFollowupRedeliveryDelayThreshold}.
     * See {@link #calculateNextRedeliveryDelay(int)}
     *
     * @return a builder for defining a {@link RedeliveryPolicy} with a Linear Backoff strategy
     */
    public static LinearBackoffBuilder linearBackoff() {
        return new LinearBackoffBuilder();
    }

    /**
     * Create a builder for defining a {@link RedeliveryPolicy} with a Fixed Backoff strategy
     *
     * @return a builder for defining a {@link RedeliveryPolicy} with a Fixed Backoff strategy
     */
    public static FixedBackoffBuilder fixedBackoff() {
        return new FixedBackoffBuilder();
    }

    RedeliveryPolicy(Duration initialRedeliveryDelay,
                            Duration followupRedeliveryDelay,
                            double followupRedeliveryDelayMultiplier,
                            Duration maximumFollowupRedeliveryDelayThreshold,
                            int maximumNumberOfRedeliveries,
                            MessageDeliveryErrorHandler deliveryErrorHandler) {
        this(initialRedeliveryDelay,
             followupRedeliveryDelay,
             followupRedeliveryDelayMultiplier,
             maximumFollowupRedeliveryDelayThreshold,
             maximumNumberOfRedeliveries,
             deliveryErrorHandler,
             false);
    }

    /**
     * A policy whose follow-up delay grows linearly, see {@link #linearBackoff(Duration, Duration, int)}
     */
    static RedeliveryPolicy linear(Duration redeliveryDelay,
                                   Duration maximumFollowupRedeliveryDelayThreshold,
                                   int maximumNumberOfRedeliveries,
                                   MessageDeliveryErrorHandler deliveryErrorHandler) {
        return new RedeliveryPolicy(redeliveryDelay,
                                    redeliveryDelay,
                                    1.0d,
                                    maximumFollowupRedeliveryDelayThreshold,
                                    maximumNumberOfRedeliveries,
                                    deliveryErrorHandler,
                                    true);
    }

    private RedeliveryPolicy(Duration initialRedeliveryDelay,
                             Duration followupRedeliveryDelay,
                             double followupRedeliveryDelayMultiplier,
                             Duration maximumFollowupRedeliveryDelayThreshold,
                             int maximumNumberOfRedeliveries,
                             MessageDeliveryErrorHandler deliveryErrorHandler,
                             boolean linearFollowupGrowth) {
        this.initialRedeliveryDelay = requireNonNull(initialRedeliveryDelay, "You must specify an initialRedeliveryDelay");
        this.followupRedeliveryDelay = requireNonNull(followupRedeliveryDelay, "You must specify an followupRedeliveryDelay");
        this.followupRedeliveryDelayMultiplier = followupRedeliveryDelayMultiplier;
        this.maximumFollowupRedeliveryThreshold = requireNonNull(maximumFollowupRedeliveryDelayThreshold, "You must specify an maximumFollowupRedeliveryDelayThreshold");
        this.maximumNumberOfRedeliveries = maximumNumberOfRedeliveries;
        this.deliveryErrorHandler = requireNonNull(deliveryErrorHandler, "You must specify a " + MessageDeliveryErrorHandler.class.getSimpleName());
        this.linearFollowupGrowth = linearFollowupGrowth;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RedeliveryPolicy that = (RedeliveryPolicy) o;
        return Double.compare(that.followupRedeliveryDelayMultiplier, followupRedeliveryDelayMultiplier) == 0 &&
                maximumNumberOfRedeliveries == that.maximumNumberOfRedeliveries &&
                linearFollowupGrowth == that.linearFollowupGrowth &&
                Objects.equals(initialRedeliveryDelay, that.initialRedeliveryDelay) &&
                Objects.equals(followupRedeliveryDelay, that.followupRedeliveryDelay) &&
                Objects.equals(maximumFollowupRedeliveryThreshold, that.maximumFollowupRedeliveryThreshold);
    }

    @Override
    public int hashCode() {
        return Objects.hash(initialRedeliveryDelay, followupRedeliveryDelay, followupRedeliveryDelayMultiplier,
                            maximumFollowupRedeliveryThreshold, maximumNumberOfRedeliveries, linearFollowupGrowth);
    }

    @Override
    public String toString() {
        return "RedeliveryPolicy{" +
                "initialRedeliveryDelay=" + initialRedeliveryDelay +
                ", followupRedeliveryDelay=" + followupRedeliveryDelay +
                ", followupRedeliveryDelayMultiplier=" + followupRedeliveryDelayMultiplier +
                ", maximumFollowupRedeliveryThreshold=" + maximumFollowupRedeliveryThreshold +
                ", maximumNumberOfRedeliveries=" + maximumNumberOfRedeliveries +
                ", linearFollowupGrowth=" + linearFollowupGrowth +
                ", deliveryErrorHandler=" + deliveryErrorHandler +
                '}';
    }

    /**
     * Calculate how long to wait before redelivering a message that just failed.
     * <ul>
     *     <li>{@code n = 0} (the first delivery failed): {@link #initialRedeliveryDelay}</li>
     *     <li>{@code n >= 1}: {@link #followupRedeliveryDelay} {@code × }{@link #followupRedeliveryDelayMultiplier}{@code ^(n-1)},
     *     capped at {@link #maximumFollowupRedeliveryThreshold}</li>
     *     <li>{@code n >= 1} for a policy from {@link #linearBackoff(Duration, Duration, int)} or {@link LinearBackoffBuilder}:
     *     {@link #initialRedeliveryDelay} {@code + }{@link #followupRedeliveryDelay}{@code  × n}, capped at {@link #maximumFollowupRedeliveryThreshold}</li>
     * </ul>
     * Examples:
     * <ul>
     *     <li>{@code exponentialBackoff(500ms, 500ms, 2.0, 1min, …)}: 500ms, 500ms, 1s, 2s, 4s, … 1min, 1min</li>
     *     <li>{@code linearBackoff(1s, 30s, …)}: 1s, 2s, 3s, … 30s, 30s</li>
     *     <li>{@code fixedBackoff(500ms, …)}: 500ms, 500ms, 500ms, …</li>
     * </ul>
     * A multiplier of {@code 1.0} gives the same {@link #followupRedeliveryDelay} on every follow-up. A multiplier below
     * {@code 1.0} (including the {@code 0.0} a {@link RedeliveryPolicyBuilder} leaves when none is set) is treated as {@code 1.0},
     * so the delay never shrinks.<br>
     * The growth is computed in floating point and clamped to the threshold before it becomes a {@link Duration},
     * so any {@code n} is safe: a large one yields the threshold, never an {@link ArithmeticException} or a negative delay.
     * <p>
     * Before 0.60 this returned {@code initialRedeliveryDelay + followupRedeliveryDelay × multiplier} for every {@code n >= 1},
     * i.e. neither the exponential nor the linear delay ever grew.
     *
     * @param currentNumberOfRedeliveryAttempts {@code n}: the failed message's {@link QueuedMessage#getRedeliveryAttempts()},
     *                                          which is 0 when its first delivery failed
     * @return the delay before the next redelivery
     */
    public Duration calculateNextRedeliveryDelay(int currentNumberOfRedeliveryAttempts) {
        requireTrue(currentNumberOfRedeliveryAttempts >= 0, "currentNumberOfRedeliveryAttempts must be 0 or larger");
        if (currentNumberOfRedeliveryAttempts == 0) {
            return initialRedeliveryDelay;
        }
        double delayNanos;
        if (linearFollowupGrowth) {
            delayNanos = toNanos(initialRedeliveryDelay) + toNanos(followupRedeliveryDelay) * currentNumberOfRedeliveryAttempts;
        } else {
            // `!(x > 1.0)` rather than `x <= 1.0` so a NaN multiplier also means "no growth"
            var multiplier = !(followupRedeliveryDelayMultiplier > 1.0d) ? 1.0d : followupRedeliveryDelayMultiplier;
            delayNanos = toNanos(followupRedeliveryDelay) * Math.pow(multiplier, currentNumberOfRedeliveryAttempts - 1);
        }
        if (delayNanos >= toNanos(maximumFollowupRedeliveryThreshold)) {
            return maximumFollowupRedeliveryThreshold;
        }
        // Below the threshold, so finite; a threshold beyond Long.MAX_VALUE nanos (~292 years) saturates rather than overflows
        return Duration.ofNanos((long) delayNanos);
    }

    /**
     * {@link Duration#toNanos()} throws beyond ~292 years; a double does not
     */
    private static double toNanos(Duration duration) {
        return duration.getSeconds() * 1_000_000_000d + duration.getNano();
    }

    /**
     * Create a {@link RedeliveryPolicy} with a Fixed Backoff strategy: every redelivery waits {@code redeliveryDelay}
     *
     * @param redeliveryDelay             the delay before every redelivery
     * @param maximumNumberOfRedeliveries the number of redeliveries before the message is marked as a Dead Letter Message
     * @return the policy
     */
    public static RedeliveryPolicy fixedBackoff(Duration redeliveryDelay,
                                                int maximumNumberOfRedeliveries) {
        return builder().setInitialRedeliveryDelay(redeliveryDelay)
                        .setFollowupRedeliveryDelay(redeliveryDelay)
                        .setFollowupRedeliveryDelayMultiplier(1.0d)
                        .setMaximumFollowupRedeliveryDelayThreshold(redeliveryDelay)
                        .setMaximumNumberOfRedeliveries(maximumNumberOfRedeliveries)
                        .setDeliveryErrorHandler(MessageDeliveryErrorHandler.alwaysRetry())
                        .build();
    }

    /**
     * Create a {@link RedeliveryPolicy} with a Linear Backoff strategy: redelivery {@code n} (counting from 0) waits
     * {@code redeliveryDelay × (n+1)}, capped at {@code maximumFollowupRedeliveryDelayThreshold}.
     * {@code linearBackoff(1s, 30s, 10)} waits 1s, 2s, 3s, … 10s and then dead-letters the message.
     *
     * @param redeliveryDelay                         the delay before the first redelivery, and the amount each later one adds
     * @param maximumFollowupRedeliveryDelayThreshold the cap on every delay after the first
     * @param maximumNumberOfRedeliveries             the number of redeliveries before the message is marked as a Dead Letter Message
     * @return the policy
     * @see #calculateNextRedeliveryDelay(int)
     */
    public static RedeliveryPolicy linearBackoff(Duration redeliveryDelay,
                                                 Duration maximumFollowupRedeliveryDelayThreshold,
                                                 int maximumNumberOfRedeliveries) {
        return linear(redeliveryDelay,
                      maximumFollowupRedeliveryDelayThreshold,
                      maximumNumberOfRedeliveries,
                      MessageDeliveryErrorHandler.alwaysRetry());
    }

    /**
     * Create a {@link RedeliveryPolicy} with an Exponential Backoff strategy.<br>
     * The first redelivery waits {@code initialRedeliveryDelay}; redelivery {@code n >= 1} waits
     * {@code followupRedeliveryDelay × followupRedeliveryDelayMultiplier^(n-1)}, capped at {@code maximumFollowupRedeliveryDelayThreshold}.
     * {@code exponentialBackoff(500ms, 500ms, 2.0, 1min, 8)} waits 500ms, 500ms, 1s, 2s, 4s, 8s, 16s, 32s and then dead-letters the message.
     *
     * @param initialRedeliveryDelay                  the delay after the first failed delivery
     * @param followupRedeliveryDelay                 the delay after the second failed delivery, and the base the multiplier grows from
     * @param followupRedeliveryDelayMultiplier       the factor each follow-up delay grows by; {@code 1.0} gives a constant follow-up delay
     * @param maximumFollowupRedeliveryDelayThreshold the cap on every follow-up delay
     * @param maximumNumberOfRedeliveries             the number of redeliveries before the message is marked as a Dead Letter Message
     * @return the policy
     * @see #calculateNextRedeliveryDelay(int)
     */
    public static RedeliveryPolicy exponentialBackoff(Duration initialRedeliveryDelay,
                                                      Duration followupRedeliveryDelay,
                                                      double followupRedeliveryDelayMultiplier,
                                                      Duration maximumFollowupRedeliveryDelayThreshold,
                                                      int maximumNumberOfRedeliveries) {
        return builder().setInitialRedeliveryDelay(initialRedeliveryDelay)
                        .setFollowupRedeliveryDelay(followupRedeliveryDelay)
                        .setFollowupRedeliveryDelayMultiplier(followupRedeliveryDelayMultiplier)
                        .setMaximumFollowupRedeliveryDelayThreshold(maximumFollowupRedeliveryDelayThreshold)
                        .setMaximumNumberOfRedeliveries(maximumNumberOfRedeliveries)
                        .setDeliveryErrorHandler(MessageDeliveryErrorHandler.alwaysRetry())
                        .build();
    }

    /**
     * If an exception occurs during message handling, the {@link #isPermanentError(QueuedMessage, Throwable)} will be called with
     * only the top-level exception - the associated {@link MessageDeliveryErrorHandler#isPermanentError(QueuedMessage, Throwable)} must itself check the error exceptions causal chain
     * to determine if it represents a permanent error.
     *
     * @param queuedMessage The message being processed by the message handler
     * @param error         the exception that occurred
     * @return true if the error represents a permanent error, otherwise false
     */
    public boolean isPermanentError(QueuedMessage queuedMessage, Throwable error) {
        return deliveryErrorHandler.isPermanentError(queuedMessage, error);
    }

    /**
     * The three-valued form of {@link #isPermanentError(QueuedMessage, Throwable)}, which the
     * {@link dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer} consults.
     *
     * @param queuedMessage The message being processed by the message handler
     * @param error         the exception that occurred
     * @return this policy's {@link MessageDeliveryErrorHandler}'s verdict on the failure
     */
    public MessageDeliveryVerdict verdict(QueuedMessage queuedMessage, Throwable error) {
        return deliveryErrorHandler.verdict(queuedMessage, error);
    }

    public Duration getInitialRedeliveryDelay() {
        return initialRedeliveryDelay;
    }

    public Duration getFollowupRedeliveryDelay() {
        return followupRedeliveryDelay;
    }

    public double getFollowupRedeliveryDelayMultiplier() {
        return followupRedeliveryDelayMultiplier;
    }

    public Duration getMaximumFollowupRedeliveryThreshold() {
        return maximumFollowupRedeliveryThreshold;
    }

    public int getMaximumNumberOfRedeliveries() {
        return maximumNumberOfRedeliveries;
    }

    public MessageDeliveryErrorHandler getDeliveryErrorHandler() {
        return deliveryErrorHandler;
    }
}
