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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MismatchedJsonInputTest {

    @Test
    void a_jackson_3_mismatched_input_is_recognised() {
        assertThat(MismatchedJsonInput.isMismatchedJsonInput(
                tools.jackson.databind.exc.MismatchedInputException.from((tools.jackson.core.JsonParser) null, String.class, "boom")))
                .isTrue();
    }

    @Test
    void a_jackson_2_mismatched_input_is_recognised() {
        assertThat(MismatchedJsonInput.isMismatchedJsonInput(
                com.fasterxml.jackson.databind.exc.MismatchedInputException.from((com.fasterxml.jackson.core.JsonParser) null, String.class, "boom")))
                .isTrue();
    }

    /** {@code instanceof} matched subclasses, so the name-based check has to walk the hierarchy to stay equivalent. */
    @Test
    void subclasses_of_either_major_are_recognised() {
        assertThat(MismatchedJsonInput.isMismatchedJsonInput(
                tools.jackson.databind.exc.InvalidFormatException.from((tools.jackson.core.JsonParser) null, "bad value", "x", Integer.class)))
                .isTrue();
        assertThat(MismatchedJsonInput.isMismatchedJsonInput(
                com.fasterxml.jackson.databind.exc.InvalidFormatException.from((com.fasterxml.jackson.core.JsonParser) null, "bad value", "x", Integer.class)))
                .isTrue();
    }

    @Test
    void other_exceptions_and_null_are_not() {
        assertThat(MismatchedJsonInput.isMismatchedJsonInput(new IllegalStateException("boom"))).isFalse();
        assertThat(MismatchedJsonInput.isMismatchedJsonInput(null)).isFalse();
    }
}
