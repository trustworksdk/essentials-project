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

/**
 * What a {@link MessageDeliveryErrorHandler} has to say about a message-handling failure.
 * <p>
 * {@link MessageDeliveryErrorHandler#isPermanentError(dk.trustworks.essentials.components.foundation.messaging.queue.QueuedMessage, Throwable)}
 * answers with a boolean, and {@code false} has always meant two different things that the consumer could not
 * tell apart: "I have no opinion, apply your own rules" and "I say retry this". That is why
 * {@code MessageDeliveryErrorHandler.builder().alwaysRetryOn(IllegalArgumentException.class)} did not work —
 * the handler answered {@code false}, the consumer OR-ed its built-in permanent list on top, and the message
 * was dead-lettered on its first delivery anyway.
 *
 * @see MessageDeliveryErrorHandler#verdict(dk.trustworks.essentials.components.foundation.messaging.queue.QueuedMessage, Throwable)
 */
public enum MessageDeliveryVerdict {
    /**
     * The handler says this failure can never succeed: dead-letter the message without spending a delivery
     * attempt on it.
     */
    PERMANENT_ERROR,

    /**
     * The handler explicitly says to retry, overriding the consumer's built-in permanent-error list where that
     * list allows it.
     * <p>
     * It does not override every built-in type — a failure that can never succeed would otherwise block the
     * head of an ordered queue forever — and it does not lift the
     * {@link RedeliveryPolicy#maximumNumberOfRedeliveries} cap.
     */
    RETRY,

    /**
     * The handler has no opinion: the consumer applies its own rules. This is what a handler that predates
     * {@code verdict} reports whenever {@code isPermanentError} returned {@code false}, and it is what
     * {@link MessageDeliveryErrorHandler#alwaysRetry()} reports.
     */
    NO_OPINION
}
