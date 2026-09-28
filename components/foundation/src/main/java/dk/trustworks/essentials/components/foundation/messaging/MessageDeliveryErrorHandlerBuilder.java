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
import dk.trustworks.essentials.shared.Exceptions;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link MessageDeliveryErrorHandler}
 */
public final class MessageDeliveryErrorHandlerBuilder {
    private List<Class<? extends Exception>> alwaysRetryOnExceptions    = List.of();
    private List<Class<? extends Exception>> stopRedeliveryOnExceptions = List.of();

    /**
     * The resulting {@link MessageDeliveryErrorHandler} will not classify the listed <code>exceptions</code> as
     * permanent errors, so message handling failures caused by them are retried according to the
     * {@link RedeliveryPolicy} instead of being marked as a Poison-Message/Dead-Letter-Message immediately.<br>
     * It will first attempt to match directly on {@link Exception} class, next it will attempt to match on hierarchy (i.e.
     * a concrete error which is a subtype of an {@link Exception} found in the <code>exceptions</code> will also match)
     * <p>
     * The listed types answer {@link MessageDeliveryVerdict#RETRY}, which overrides the consumer's built-in
     * permanent-error list for {@link IllegalArgumentException} (including {@link NumberFormatException}) and
     * {@link ClassCastException}. It does <em>not</em> override {@code DurableQueueDeserializationException},
     * {@code MismatchedInputException} or {@link NoClassDefFoundError}: those can never succeed on a later
     * attempt, and retrying one forever would block the head of an ordered queue.
     * <p>
     * <b>This does not mean unlimited redelivery.</b> The {@link RedeliveryPolicy}'s
     * {@link RedeliveryPolicy#maximumNumberOfRedeliveries} cap is enforced by the {@link DurableQueueConsumer}
     * regardless of this setting, so a message that keeps failing is still dead-lettered once its delivery
     * attempts are exhausted.
     *
     * @param exceptions the exceptions that this handler will not classify as permanent errors
     * @return this builder instance
     */
    @SafeVarargs
    public final MessageDeliveryErrorHandlerBuilder alwaysRetryOn(Class<? extends Exception>... exceptions) {
        return alwaysRetryOn(List.of(exceptions));
    }

    /**
     * The resulting {@link MessageDeliveryErrorHandler} will not classify the listed <code>exceptions</code> as
     * permanent errors, so message handling failures caused by them are retried according to the
     * {@link RedeliveryPolicy} instead of being marked as a Poison-Message/Dead-Letter-Message immediately.<br>
     * It will first attempt to match directly on {@link Exception} class, next it will attempt to match on hierarchy (i.e.
     * a concrete error which is a subtype of an {@link Exception} found in the <code>exceptions</code> will also match)
     * <p>
     * <b>This does not mean unlimited redelivery</b>, and it does not override every built-in permanent-error
     * type. See {@link #alwaysRetryOn(Class[])} for both limits.
     *
     * @param exceptions the exceptions that this handler will not classify as permanent errors
     * @return this builder instance
     */
    public MessageDeliveryErrorHandlerBuilder alwaysRetryOn(List<Class<? extends Exception>> exceptions) {
        alwaysRetryOnExceptions = requireNonNull(exceptions, "No exceptions list provided");
        return this;
    }

    /**
     * The resulting {@link MessageDeliveryErrorHandler} will stop message redelivery in case
     * message handling experiences an exception for in the list of <code>exceptions</code>.<br>
     * It will first attempt to match directly on {@link Exception} class, next it will attempt to match on hierarchy (i.e.
     * a concrete error which is a subtype of an {@link Exception} found in the <code>exceptions</code> will also match)
     *
     * @param exceptions the exceptions where message redelivery will be stopped and the Message will be
     *                   instantly marked as a Poison-Message/Dead-Letter-Message
     * @return this builder instance
     */
    @SafeVarargs
    public final MessageDeliveryErrorHandlerBuilder stopRedeliveryOn(Class<? extends Exception>... exceptions) {
        return stopRedeliveryOn(List.of(exceptions));
    }

    /**
     * The resulting {@link MessageDeliveryErrorHandler} will stop message redelivery in case
     * message handling experiences an exception for in the list of <code>exceptions</code>.<br>
     * It will first attempt to match directly on {@link Exception} class, next it will attempt to match on hierarchy (i.e.
     * a concrete error which is a subtype of an {@link Exception} found in the <code>exceptions</code> will also match)
     *
     * @param exceptions the exceptions where message redelivery will be stopped and the Message will be
     *                   instantly marked as a Poison-Message/Dead-Letter-Message
     * @return this builder instance
     */
    public MessageDeliveryErrorHandlerBuilder stopRedeliveryOn(List<Class<? extends Exception>> exceptions) {
        stopRedeliveryOnExceptions = requireNonNull(exceptions, "No exceptions list provided");
        return this;
    }

    @Override
    public String toString() {
        return "MessageDeliveryErrorHandlerBuilder{" +
                "alwaysRetryOnExceptions=" + alwaysRetryOnExceptions +
                ", stopRedeliveryOnExceptions=" + stopRedeliveryOnExceptions +
                '}';
    }

    public MessageDeliveryErrorHandler build() {

        var stopRedeliveryOnHandler = MessageDeliveryErrorHandler.stopRedeliveryOn(stopRedeliveryOnExceptions);
        return new MessageDeliveryErrorHandler() {
            @Override
            public boolean isPermanentError(QueuedMessage queuedMessage, Throwable error) {
                if (shouldAlwaysRetryOn(error)) {
                    return false;
                }
                return stopRedeliveryOnHandler.isPermanentError(queuedMessage, error);
            }

            /**
             * Unlike the default mapping, an {@code alwaysRetryOn} match answers {@link MessageDeliveryVerdict#RETRY}
             * rather than {@link MessageDeliveryVerdict#NO_OPINION} — that is the whole point of listing a type
             * there, and without it the consumer's built-in permanent list would still dead-letter the message.
             * <p>
             * Note that only the explicit {@code alwaysRetryOn} list yields {@code RETRY}. An empty list — which
             * is the builder's default, and what {@link MessageDeliveryErrorHandler#alwaysRetry()} amounts to —
             * keeps answering {@code NO_OPINION}, so deserialization failures do not suddenly retry forever in
             * applications that never asked for it.
             */
            @Override
            public MessageDeliveryVerdict verdict(QueuedMessage queuedMessage, Throwable error) {
                if (shouldAlwaysRetryOn(error)) {
                    return MessageDeliveryVerdict.RETRY;
                }
                return stopRedeliveryOnHandler.isPermanentError(queuedMessage, error)
                       ? MessageDeliveryVerdict.PERMANENT_ERROR
                       : MessageDeliveryVerdict.NO_OPINION;
            }

            private boolean shouldAlwaysRetryOn(Throwable error) {
                return alwaysRetryOnExceptions.contains(error.getClass()) ||
                        alwaysRetryOnExceptions.stream()
                                               .anyMatch(alwaysRetryOnException -> Exceptions.doesStackTraceContainExceptionOfType(error, alwaysRetryOnException));
            }

            @Override
            public String toString() {
                return "MessageDeliveryErrorHandler{" +
                        "alwaysRetryOnExceptions=" + alwaysRetryOnExceptions +
                        ", stopRedeliveryOnExceptions=" + stopRedeliveryOnExceptions +
                        '}';
            }
        };
    }
}
