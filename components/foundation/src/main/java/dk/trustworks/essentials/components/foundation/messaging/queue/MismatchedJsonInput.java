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

import java.util.Set;

/**
 * Recognises Jackson's {@code MismatchedInputException} - JSON that cannot be bound to the target type, which no
 * redelivery will fix - without referencing the class.
 * <p>
 * Jackson 2 ({@code com.fasterxml.jackson.databind.exc}) and Jackson 3 ({@code tools.jackson.databind.exc}) ship the
 * type in different packages, and an application normally has only one of them. An {@code instanceof} against the
 * Jackson 2 class therefore never matched a Jackson 3 exception, so under the default Jackson 3 flavor such a message
 * was retried as if the failure were transient; and on a runtime without Jackson 2 the check itself threw
 * {@link NoClassDefFoundError} while handling the original failure. Matching by name covers both majors and links on
 * either. Subclasses such as {@code InvalidFormatException} are matched too, as {@code instanceof} did.
 */
final class MismatchedJsonInput {
    private static final Set<String> MISMATCHED_INPUT_CLASS_NAMES = Set.of("com.fasterxml.jackson.databind.exc.MismatchedInputException",
                                                                           "tools.jackson.databind.exc.MismatchedInputException");

    private MismatchedJsonInput() {
    }

    /**
     * @param throwable the exception to test (typically the root cause of a delivery failure); may be {@code null}
     * @return {@code true} if {@code throwable} is a Jackson 2 or Jackson 3 {@code MismatchedInputException}, or a subclass of one
     */
    static boolean isMismatchedJsonInput(Throwable throwable) {
        for (Class<?> type = throwable == null ? null : throwable.getClass(); type != null; type = type.getSuperclass()) {
            if (MISMATCHED_INPUT_CLASS_NAMES.contains(type.getName())) {
                return true;
            }
        }
        return false;
    }
}
