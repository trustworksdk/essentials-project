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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The step-down schedule: halve on a failed page, keep the size inside the suspect range, double
 * after each committed page above it, up to the page size
 */
class StepDownTest {
    @Test
    void starts_at_the_page_size() {
        assertThat(new StepDown(100, false).size()).isEqualTo(100);
    }

    @Test
    void a_failed_page_halves_the_size_and_becomes_the_suspect_range() {
        var stepDown = new StepDown(100, false);
        stepDown.failed(1, 100, 100);
        assertThat(stepDown.size()).isEqualTo(50);
        assertThat(stepDown.hasSuspectRange()).isTrue();
        assertThat(stepDown.suspectLow()).isEqualTo(1);
        assertThat(stepDown.suspectHigh()).isEqualTo(100);
        stepDown.failed(1, 1, 1);
        assertThat(stepDown.size()).isEqualTo(1);
    }

    @Test
    void a_page_committed_inside_the_suspect_range_keeps_the_size() {
        var stepDown = new StepDown(100, false);
        stepDown.failed(1, 100, 100);
        stepDown.failed(1, 50, 50);
        stepDown.committed(1, 25);
        assertThat(stepDown.size()).isEqualTo(25);
        assertThat(stepDown.hasSuspectRange()).isTrue();
    }

    @Test
    void above_the_suspect_range_the_size_doubles_after_each_committed_page_up_to_the_page_size() {
        var stepDown = new StepDown(100, false);
        stepDown.failed(1, 100, 100);
        stepDown.failed(1, 50, 50);
        stepDown.failed(1, 25, 25);
        stepDown.failed(1, 12, 12);
        stepDown.failed(1, 6, 6);
        stepDown.failed(1, 3, 3);
        assertThat(stepDown.size()).isEqualTo(1);
        stepDown.committed(1, 1);
        assertThat(stepDown.size()).isEqualTo(1);
        var sizes = new ArrayList<Integer>();
        var next  = 2L;
        for (var i = 0; i < 10; i++) {
            var size = stepDown.size();
            stepDown.committed(next, next + size - 1);
            next += size;
            sizes.add(stepDown.size());
        }
        assertThat(stepDown.hasSuspectRange()).isFalse();
        // 2 and 3 are still inside the suspect range [1, 3]
        assertThat(sizes).containsExactly(1, 1, 2, 4, 8, 16, 32, 64, 100, 100);
    }

    /** One event at offset 37 of a page of 100 fails until it fails alone once (EventLogPageFailureIT (a)) */
    @Test
    void isolating_one_event_in_a_page_of_100_costs_at_most_seven_failed_pages() {
        assertThat(failedPagesToIsolate(new StepDown(100, false), 37, 100)).isEqualTo(7);
    }

    @Test
    void growing_inside_the_suspect_range_costs_more_failed_pages() {
        assertThat(failedPagesToIsolate(new StepDown(100, true), 37, 100)).isGreaterThan(8);
    }

    @Test
    void every_offset_of_a_page_of_100_is_isolated_within_seven_failed_pages() {
        for (var offset = 0; offset < 100; offset++) {
            assertThat(failedPagesToIsolate(new StepDown(100, false), offset, 100)).as("offset %d", offset).isLessThanOrEqualTo(7);
        }
    }

    /**
     * Pages over offsets {@code 0..events-1}; the event at {@code failing} fails in every page it is in until it has
     * failed alone once
     *
     * @return the failed pages until it committed
     */
    private static int failedPagesToIsolate(StepDown stepDown, int failing, int events) {
        var failed = 0;
        var next   = 0;
        var alone  = false;
        while (next < events) {
            var size = Math.min(stepDown.size(), events - next);
            var last = next + size - 1;
            if (failing >= next && failing <= last && !alone) {
                failed++;
                if (size == 1) {
                    alone = true;
                } else {
                    stepDown.failed(next, last, size);
                }
                continue;
            }
            stepDown.committed(next, last);
            next = last + 1;
        }
        return failed;
    }
}
