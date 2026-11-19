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
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The window schedule (doubling, cap, quartering on cancel) and the density switch */
class CatchUpWindowTest {
    @Test
    void a_short_page_doubles_the_window_up_to_the_cap() {
        var window = new CatchUpWindow(100_000, 350_000, 500);
        assertThat(window.ceiling(1_000, 10_000_000)).isEqualTo(101_000);
        window.afterRead(false, 3, 100_000);
        assertThat(window.window()).isEqualTo(200_000);
        window.afterRead(false, 0, 200_000);
        assertThat(window.window()).isEqualTo(350_000);
        window.afterRead(false, 0, 350_000);
        assertThat(window.window()).isEqualTo(350_000);
    }

    @Test
    void a_full_page_keeps_the_window_and_the_ceiling_never_passes_the_watermark() {
        var window = new CatchUpWindow(100_000, 1_000_000, 500);
        window.afterRead(true, 500, 60_000);
        assertThat(window.window()).isEqualTo(100_000);
        assertThat(window.ceiling(10, 50)).isEqualTo(50);
        assertThat(window.ceiling(Long.MAX_VALUE - 5, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void a_cancel_quarters_the_window_but_not_below_the_page_size() {
        var window = new CatchUpWindow(100_000, 1_000_000, 500);
        window.afterCancel();
        assertThat(window.window()).isEqualTo(25_000);
        for (var i = 0; i < 10; i++) {
            window.afterCancel();
        }
        assertThat(window.window()).isEqualTo(500);
        window.reset();
        assertThat(window.window()).isEqualTo(100_000);
    }

    @Test
    void density_switches_with_hysteresis() {
        var window = new CatchUpWindow(1_000, 1_000_000, 100);
        window.afterRead(true, 100, 200);
        assertThat(window.dense()).as("0.5 > 0.25").isTrue();
        window.afterRead(false, 10, 100);
        assertThat(window.dense()).as("0.10 is between the thresholds").isTrue();
        window.afterRead(false, 1, 100);
        assertThat(window.dense()).as("0.01 < 0.05").isFalse();
        window.afterRead(false, 0, 1_000);
        assertThat(window.dense()).isFalse();
    }
}
