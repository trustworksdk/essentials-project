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

package dk.trustworks.essentials.components.eventlog.integration;

import dk.trustworks.essentials.components.eventlog.integration.test_data.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DispatchTest {
    @Test
    void the_exact_class_wins_over_a_supertype() {
        var dispatch = new Dispatch<>(Map.of(Added.class, "exact", OrderEvent.class, "interface", Object.class, "object"), false, "test");
        assertThat(dispatch.handlerFor(Added.class)).hasValue("exact");
    }

    @Test
    void a_superclass_wins_over_an_interface_and_an_interface_is_found() {
        assertThat(new Dispatch<>(Map.of(OrderEvent.class, "interface", Object.class, "object"), false, "test").handlerFor(Added.class)).hasValue("object");
        assertThat(new Dispatch<>(Map.of(OrderEvent.class, "interface"), false, "test").handlerFor(Added.class)).hasValue("interface");
    }

    @Test
    void an_unknown_type_counts_as_handled_unless_strict() {
        assertThat(new Dispatch<>(Map.of(Added.class, "x"), false, "test").handlerFor(String.class)).isEmpty();
        var strict = new Dispatch<>(Map.of(Added.class, "x"), true, "test");
        assertThatThrownBy(() -> strict.handlerFor(String.class)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.handlerFor(String.class)).as("cached, still strict").isInstanceOf(IllegalStateException.class);
    }
}
