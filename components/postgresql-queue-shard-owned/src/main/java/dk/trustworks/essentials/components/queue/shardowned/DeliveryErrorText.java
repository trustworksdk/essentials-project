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

import dk.trustworks.essentials.shared.Exceptions;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Renders the error text both owners store as a dead letter's {@code last_error}.
 * <p>
 * A handler's own exception usually reaches the owner wrapped - by the adapter's unit of work, by reflection, or by
 * the application itself - so the outermost throwable alone names a wrapper and hides the reason the message failed.
 * When the throwable is wrapped, the text therefore starts with a {@value Exceptions#ROOT_CAUSE_PREFIX} line naming
 * the root cause, followed by the outermost throwable. An unwrapped throwable is stored as its
 * {@link Throwable#toString()} alone. That matches 0.60.0's {@code <type>: <message>} except for a throwable without a
 * message, now stored as {@code <type>} rather than {@code <type>: null}, and one that overrides
 * {@link Throwable#toString()} or {@link Throwable#getLocalizedMessage()}, now stored in its own format. The root-cause
 * line is {@link Exceptions#prependRootCauseLine(Throwable, String)}'s, the same line the database-backed durable
 * queues put in front of their stored stack trace; only the body differs: the engine stores the outermost throwable's
 * {@link Throwable#toString()}, not a stack trace.
 */
final class DeliveryErrorText {
    private DeliveryErrorText() {
    }

    /**
     * @param cause the delivery error
     * @return the {@link Throwable#toString()} of <code>cause</code> ({@code <type>: <message>}, or {@code <type>}
     * alone when it has no message), preceded by a root-cause line of the same shape when <code>cause</code> wraps another throwable
     */
    static String describe(Throwable cause) {
        requireNonNull(cause, "No cause provided");
        return Exceptions.prependRootCauseLine(cause, cause.toString());
    }
}
