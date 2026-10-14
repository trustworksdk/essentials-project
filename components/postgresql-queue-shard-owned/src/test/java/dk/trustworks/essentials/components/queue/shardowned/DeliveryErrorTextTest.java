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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text a dead letter records must name the handler's own exception, not just the wrapper it reached the
 * owner in.
 */
class DeliveryErrorTextTest {

    @Test
    void an_unwrapped_throwable_is_rendered_as_before() {
        assertThat(DeliveryErrorText.describe(new IllegalStateException("boom")))
                .isEqualTo("java.lang.IllegalStateException: boom");
    }

    @Test
    void a_wrapped_throwable_starts_with_its_root_cause() {
        var cause = new RuntimeException("wrapper",
                                         new IllegalArgumentException("middle",
                                                                      new IllegalStateException("the real reason")));

        assertThat(DeliveryErrorText.describe(cause))
                .isEqualTo("Root cause: java.lang.IllegalStateException: the real reason" + System.lineSeparator()
                                   + "java.lang.RuntimeException: wrapper");
    }

    @Test
    void a_cause_cycle_does_not_loop() {
        var outer = new RuntimeException("outer");
        var inner = new IllegalStateException("inner", outer);
        outer.initCause(inner);

        assertThat(DeliveryErrorText.describe(outer))
                .startsWith(Exceptions.ROOT_CAUSE_PREFIX + "java.lang.IllegalStateException: inner")
                .endsWith("java.lang.RuntimeException: outer");
    }
}
