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

import dk.trustworks.essentials.shared.Exceptions;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Renders the text a {@link DurableQueues} implementation stores as a message's {@link QueuedMessage#getLastDeliveryError()}.
 * <p>
 * A {@code @MessageHandler} throw reaches the queue wrapped in the framework's own exceptions —
 * {@code UnitOfWorkException → ReflectionException → InvocationTargetException → yours} — so the first line of the
 * plain stack trace almost always reads "UnitOfWorkException", and the handler's own exception sits several
 * "Caused by:" sections further down. When the throwable is wrapped, the text therefore starts with a one-line
 * summary of the root cause, followed by the complete, unaltered stack trace, so nothing is lost.
 * An unwrapped throwable is rendered exactly as {@link Exceptions#getStackTrace(Throwable)} renders it.
 * <p>
 * The summary line is {@link Exceptions#prependRootCauseLine(Throwable, String)}'s, which the shard-owned engine
 * uses for its dead letters too, so the line has one shape across every queue engine.
 */
public final class DeliveryErrorDescription {
    /**
     * Prefix of the root-cause summary line that precedes the stack trace of a wrapped throwable
     * (the same value as {@link Exceptions#ROOT_CAUSE_PREFIX})
     */
    public static final String ROOT_CAUSE_PREFIX = Exceptions.ROOT_CAUSE_PREFIX;

    private DeliveryErrorDescription() {
    }

    /**
     * @param cause the delivery error
     * @return a root-cause summary line (only when <code>cause</code> wraps another throwable) followed by the full stack trace of <code>cause</code>
     */
    public static String describe(Throwable cause) {
        requireNonNull(cause, "No cause provided");
        return Exceptions.prependRootCauseLine(cause, Exceptions.getStackTrace(cause));
    }
}
