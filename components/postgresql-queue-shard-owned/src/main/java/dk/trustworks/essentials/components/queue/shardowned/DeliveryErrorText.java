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
 * When the throwable is wrapped, the text therefore starts with a {@value #ROOT_CAUSE_PREFIX} line naming the root
 * cause, followed by the outermost throwable as before. An unwrapped throwable is rendered exactly as it always was.
 * This is the same summary the database-backed durable queues put in front of their stored stack trace; the engine
 * renders it itself because it does not depend on {@code foundation}.
 */
final class DeliveryErrorText {
    /**
     * Prefix of the root-cause line that precedes the outermost throwable when that throwable is wrapped
     */
    static final String ROOT_CAUSE_PREFIX = "Root cause: ";

    private DeliveryErrorText() {
    }

    /**
     * @param cause the delivery error
     * @return {@code <type>: <message>} of <code>cause</code>, preceded by a root-cause line of the same shape when
     * <code>cause</code> wraps another throwable
     */
    static String describe(Throwable cause) {
        requireNonNull(cause, "No cause provided");
        var outermost = oneLine(cause);
        var rootCause = Exceptions.getRootCause(cause);
        if (rootCause == cause) {
            return outermost;
        }
        return ROOT_CAUSE_PREFIX + oneLine(rootCause) + System.lineSeparator() + outermost;
    }

    private static String oneLine(Throwable throwable) {
        return String.valueOf(throwable);
    }
}
