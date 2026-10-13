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

import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException;
import dk.trustworks.essentials.shared.Exceptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryErrorDescriptionTest {
    @Test
    void an_unwrapped_exception_is_rendered_as_its_plain_stack_trace() {
        var cause = new IllegalStateException("handler failed");

        assertThat(DeliveryErrorDescription.describe(cause)).isEqualTo(Exceptions.getStackTrace(cause));
    }

    @Test
    void a_wrapped_exception_leads_with_the_root_cause_and_keeps_the_full_stack_trace() {
        var handlerException = new IllegalStateException("handler failed");
        var wrapped          = new UnitOfWorkException(new InvocationTargetException(handlerException));

        var description = DeliveryErrorDescription.describe(wrapped);

        assertThat(description.lines().findFirst())
                .hasValue(DeliveryErrorDescription.ROOT_CAUSE_PREFIX + "java.lang.IllegalStateException: handler failed");
        assertThat(description).endsWith(Exceptions.getStackTrace(wrapped));
    }
}
