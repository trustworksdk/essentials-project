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

/**
 * Phase 3 §3.2: the prefix {@code P}, the run-length runs above it, their pruning, and the dedupe rule.
 */
class HandledSetTest {
    @Test
    void everything_at_or_below_the_prefix_is_handled() {
        var set = new HandledSet(10);
        assertThat(set.contains(1)).isTrue();
        assertThat(set.contains(10)).isTrue();
        assertThat(set.contains(11)).isFalse();
        assertThat(set.resumeFrom()).isEqualTo(11);
    }

    @Test
    void handled_orders_above_the_prefix_form_runs_that_merge() {
        var set = new HandledSet(10);
        set.add(13);
        set.add(15);
        assertThat(set.runCount()).isEqualTo(2);
        set.add(14);
        assertThat(set.runCount()).as("13-15 merged").isEqualTo(1);
        set.add(12);
        set.add(20);
        assertThat(set.runCount()).isEqualTo(2);
        assertThat(set.contains(11)).isFalse();
        assertThat(set.contains(12)).isTrue();
        assertThat(set.contains(15)).isTrue();
        assertThat(set.contains(16)).isFalse();
        assertThat(set.contains(20)).isTrue();
        assertThat(set.highest()).isEqualTo(20);
        assertThat(set.prefix()).as("adding never moves P").isEqualTo(10);
    }

    @Test
    void adding_an_order_already_handled_changes_nothing() {
        var set = new HandledSet(10);
        set.add(5);
        set.add(12);
        set.add(12);
        assertThat(set.runCount()).isEqualTo(1);
        assertThat(set.contains(12)).isTrue();
    }

    @Test
    void advancing_the_prefix_prunes_and_trims_runs() {
        var set = new HandledSet(10);
        set.add(12);
        set.add(13);
        set.add(15);
        set.add(16);
        set.add(17);
        set.add(30);
        set.advanceTo(16);
        assertThat(set.prefix()).isEqualTo(16);
        assertThat(set.runCount()).as("12-13 pruned, 15-17 trimmed to 17, 30 kept").isEqualTo(2);
        assertThat(set.contains(17)).isTrue();
        assertThat(set.contains(18)).isFalse();
        assertThat(set.contains(30)).isTrue();
        set.advanceTo(40);
        assertThat(set.runCount()).isZero();
        assertThat(set.highest()).isEqualTo(40);
    }

    @Test
    void the_prefix_never_moves_down() {
        var set = new HandledSet(10);
        set.add(12);
        set.advanceTo(5);
        assertThat(set.prefix()).isEqualTo(10);
        assertThat(set.contains(12)).isTrue();
    }

    @Test
    void a_reset_repositions_and_forgets_the_runs() {
        var set = new HandledSet(10);
        set.add(12);
        set.reset(3);
        assertThat(set.prefix()).isEqualTo(3);
        assertThat(set.contains(4)).isFalse();
        assertThat(set.contains(12)).isFalse();
        assertThat(set.runCount()).isZero();
    }
}
