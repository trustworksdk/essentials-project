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

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.shared.Exceptions;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Decides what happens to a message whose handler threw: dead-letter it now, dead-letter it because its
 * delivery attempts are used up, or redeliver it.
 * <p>
 * This logic used to be copied verbatim into {@link DefaultDurableQueueConsumer} and
 * {@link CentralizedMessageFetcher} — both the classification and the
 * {@code isPermanentError || attempts >= max + 1} condition that consumed it. Two copies of a rule that is
 * being changed are two copies free to drift, so both consumers now call this instead.
 *
 * <h2>The policy is consulted first, then the built-in list wins</h2>
 * The {@link RedeliveryPolicy}'s {@link MessageDeliveryErrorHandler} is asked first, and the built-in list of
 * permanent error types is OR-ed on top. The consequence is that no policy can remove a type from the built-in
 * list — notably {@link IllegalArgumentException}, which {@code FailFast.requireNonNull} / {@code requireTrue}
 * and Kotlin's {@code require(...)} all throw, so a handler that validates its arguments dead-letters its
 * message on the first delivery attempt. See {@code LLM/LLM-foundation.md}.
 *
 * <h2>Only the ends of the cause chain are examined</h2>
 * Matching tests the thrown exception and {@link Exceptions#getRootCause(Throwable)}, never the middle. A
 * handler throw arrives wrapped ({@code UnitOfWorkException → ReflectionException → InvocationTargetException
 * → yours}), so the handler's own exception is normally the deepest and decides — unless it carries a cause of
 * its own, at which point classification silently switches to that deeper type.
 */
public final class MessageDeliveryClassifier {

    private MessageDeliveryClassifier() {
    }

    /**
     * Decide what to do with {@code queuedMessage} after its handler threw {@code error}.
     *
     * @param queuedMessage    the message whose delivery failed
     * @param error            the error the handler threw
     * @param redeliveryPolicy the policy in force for the consumer that was delivering the message
     * @return the outcome the consumer should apply
     */
    public static MessageDeliveryOutcome classify(QueuedMessage queuedMessage,
                                                  Throwable error,
                                                  RedeliveryPolicy redeliveryPolicy) {
        requireNonNull(queuedMessage, "No queuedMessage provided");
        requireNonNull(error, "No error provided");
        requireNonNull(redeliveryPolicy, "No redeliveryPolicy provided");

        if (isPermanentError(queuedMessage, error, redeliveryPolicy)) {
            return MessageDeliveryOutcome.PERMANENT_ERROR;
        }
        if (queuedMessage.getTotalDeliveryAttempts() >= redeliveryPolicy.getMaximumNumberOfRedeliveries() + 1) {
            return MessageDeliveryOutcome.REDELIVERIES_EXHAUSTED;
        }
        return MessageDeliveryOutcome.RETRY;
    }

    /**
     * Whether {@code error} is classified as permanent — by the policy's {@link MessageDeliveryErrorHandler}, or
     * by the built-in list which is applied afterwards and cannot be overridden.
     *
     * @param queuedMessage    the message whose delivery failed
     * @param error            the error the handler threw
     * @param redeliveryPolicy the policy in force for the consumer that was delivering the message
     * @return true if the message should be dead-lettered without consuming a delivery attempt
     */
    public static boolean isPermanentError(QueuedMessage queuedMessage,
                                           Throwable error,
                                           RedeliveryPolicy redeliveryPolicy) {
        return redeliveryPolicy.isPermanentError(queuedMessage, error) || isBuiltInPermanentError(error);
    }

    /**
     * The framework's own list of error types that are always permanent, whatever the {@link RedeliveryPolicy}
     * says.
     * <p>
     * <b>{@link MismatchedInputException} is Jackson 2's.</b> Under a Jackson 3 runtime the deserializer throws
     * {@code tools.jackson.databind.exc.MismatchedInputException} instead, which this does not match, and on a
     * runtime carrying only Jackson 3 the {@code instanceof} cannot even link. The reference is kept here
     * unchanged because extracting this method must not change behaviour; it is the built-in list's own problem
     * to fix, not the extraction's.
     *
     * @param error the error the handler threw
     * @return true if {@code error}, or its root cause, is one of the always-permanent types
     */
    public static boolean isBuiltInPermanentError(Throwable error) {
        var rootCause = Exceptions.getRootCause(error);
        return error instanceof DurableQueueDeserializationException ||
                error instanceof ClassCastException || rootCause instanceof ClassCastException ||
                error instanceof NoClassDefFoundError || rootCause instanceof NoClassDefFoundError ||
                rootCause instanceof MismatchedInputException ||
                error instanceof IllegalArgumentException || rootCause instanceof IllegalArgumentException;
    }
}
