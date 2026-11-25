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

package dk.trustworks.essentials.examples.trading._demo_harness.demo_control;

import dk.trustworks.essentials.components.eventlog.ParkEventException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ViewFaultSwitchTest {
    private final ViewFaultSwitch viewFault = new ViewFaultSwitch();

    @Test
    void off_lets_the_handler_run() {
        assertThatCode(() -> viewFault.check("the orders view")).doesNotThrowAnyException();
        assertThat(viewFault.status()).isEqualTo(new ViewFaultSwitch.Status(ViewFaultSwitch.Mode.OFF, 0));
    }

    @Test
    void retrying_throws_an_ordinary_exception_the_engine_retries() {
        viewFault.set(ViewFaultSwitch.Mode.RETRYING);
        assertThatThrownBy(() -> viewFault.check("the orders view"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ParkEventException.class);
        assertThat(viewFault.status().failuresThrown()).isEqualTo(1);
    }

    @Test
    void terminal_throws_a_park_event_exception() {
        viewFault.set(ViewFaultSwitch.Mode.TERMINAL);
        assertThatThrownBy(() -> viewFault.check("the orders view")).isInstanceOf(ParkEventException.class);
    }

    @Test
    void switching_off_again_lets_the_handler_run() {
        viewFault.set(ViewFaultSwitch.Mode.TERMINAL);
        viewFault.set(ViewFaultSwitch.Mode.OFF);
        assertThatCode(() -> viewFault.check("the orders view")).doesNotThrowAnyException();
    }
}
