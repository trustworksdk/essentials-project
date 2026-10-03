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

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The range rendering behind the ownership log lines.
 * <p>
 * Worth its own test despite being presentation: it replaced one line per unit, so it is now the only
 * statement of which units moved, and a summary that is quietly wrong is worse than the noise it
 * replaced — an operator reading "[32-54]" has no way to tell it should have said "[32-53,55]". The
 * run-collapsing loop is also exactly the shape that goes wrong at the ends.
 */
class ShardSummaryTest {

    @Test
    void test_a_contiguous_run_is_rendered_as_a_range() {
        assertThat(ShardOwnedQueue.summarise(List.of(32, 33, 34, 35))).isEqualTo("32-35");
    }

    @Test
    void test_a_gap_splits_the_run() {
        assertThat(ShardOwnedQueue.summarise(List.of(0, 1, 2, 4, 5, 9))).isEqualTo("0-2,4-5,9");
    }

    @Test
    void test_single_units_are_rendered_alone_rather_than_as_a_range() {
        assertThat(ShardOwnedQueue.summarise(List.of(7))).isEqualTo("7");
        assertThat(ShardOwnedQueue.summarise(List.of(7, 9, 11))).isEqualTo("7,9,11");
    }

    /** A pair is the boundary the loop is most likely to get wrong — "3-4", never "3,4" or "3-3". */
    @Test
    void test_a_pair_is_a_range_and_a_run_of_one_is_not() {
        assertThat(ShardOwnedQueue.summarise(List.of(3, 4))).isEqualTo("3-4");
        assertThat(ShardOwnedQueue.summarise(List.of(3, 5))).isEqualTo("3,5");
    }

    /** The acquire loops collect in the order they moved units, which is not required to be sorted. */
    @Test
    void test_unsorted_input_is_rendered_in_order() {
        assertThat(ShardOwnedQueue.summarise(List.of(34, 32, 33))).isEqualTo("32-34");
    }

    @Test
    void test_an_empty_pass_renders_as_nothing() {
        assertThat(ShardOwnedQueue.summarise(List.of())).isEmpty();
    }

    /** The case that motivated the change: one instance taking a whole ordered lane. */
    @Test
    void test_a_whole_ordered_lane_is_one_range() {
        var everyUnit = new ArrayList<Integer>();
        for (var unit = 0; unit < ShardOwnedSchema.ORDERED_UNITS; unit++) {
            everyUnit.add(unit);
        }
        assertThat(ShardOwnedQueue.summarise(everyUnit))
                .isEqualTo("0-" + (ShardOwnedSchema.ORDERED_UNITS - 1));
    }
}
