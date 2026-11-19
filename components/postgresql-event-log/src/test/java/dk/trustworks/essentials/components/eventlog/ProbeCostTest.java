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

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The probe clock's cost input: a flush of several types must not record its whole wall time as a
 * type's probe cost, or the cost rule ({@link EventLogSettings#wokenProbeWait}) turns a few milliseconds of tail reads into
 * seconds before the next running set, and the watermark stalls behind a candidate that could have retired.
 * {@link WatermarkMutant#FLUSH_COUNTED_AS_PROBE_COST} restores the old behaviour. And the cap while a hole waits: even an
 * honest probe cost, multiplied by the rule, holds a rolled-back append's hole for seconds, so the wait is capped at
 * {@link EventLogSettings#holeProbeWaitMax()} then ({@link WatermarkMutant#HOLE_PROBE_WAIT_UNCAPPED}).
 */
class ProbeCostTest {
    private static final long             PREVIOUS_COST = Duration.ofNanos(300_000).toNanos();
    private static final long             FLUSH_NANOS   = Duration.ofMillis(5).toNanos();
    private static final int              BACKENDS      = 66;
    private static final EventLogSettings SETTINGS      = EventLogSettings.defaults();

    private static TailReader.Read flushOfSeveralTypes() {
        return new TailReader.Read(List.of(), TailReader.RunningSet.NONE, FLUSH_NANOS, true, false);
    }

    private static Duration nextWokenProbeWait(TailReader.Read read, TestSeams seams) {
        return SETTINGS.wokenProbeWait(ReaderLoop.probeCostAfter(PREVIOUS_COST, read, seams), BACKENDS);
    }

    @Test
    void a_flush_of_several_types_keeps_the_previous_probe_cost() {
        assertThat(ReaderLoop.probeCostAfter(PREVIOUS_COST, flushOfSeveralTypes(), TestSeams.NONE)).isEqualTo(PREVIOUS_COST);
        assertThat(nextWokenProbeWait(flushOfSeveralTypes(), TestSeams.NONE)).isEqualTo(SETTINGS.wokenProbeWait(PREVIOUS_COST, BACKENDS))
                                                                             .isLessThan(Duration.ofMillis(250));
    }

    @Test
    void must_fail_FLUSH_COUNTED_AS_PROBE_COST_inflates_the_next_probe_wait() {
        var seams = new TestSeams(Set.of(WatermarkMutant.FLUSH_COUNTED_AS_PROBE_COST), TestSeams.Hooks.NONE, null);
        // 5 ms x 100 x (66 / 10): the next running set three seconds out
        assertThat(nextWokenProbeWait(flushOfSeveralTypes(), seams)).isGreaterThanOrEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void a_probe_measured_on_its_own_sets_the_cost() {
        var probe = new TailReader.Read(List.of(), TailReader.RunningSet.NONE, FLUSH_NANOS, true, true);
        assertThat(ReaderLoop.probeCostAfter(PREVIOUS_COST, probe, TestSeams.NONE)).isEqualTo(FLUSH_NANOS);
    }

    @Test
    void a_read_without_a_running_set_keeps_the_previous_cost() {
        var tail = new TailReader.Read(List.of(), null, FLUSH_NANOS, false, true);
        assertThat(ReaderLoop.probeCostAfter(PREVIOUS_COST, tail, TestSeams.NONE)).isEqualTo(PREVIOUS_COST);
    }

    // ---- the cap while a hole waits ----

    private static final long HONEST_PROBE = Duration.ofMillis(5).toNanos();
    private static final int  POOL_60     = 60;

    @Test
    void while_a_hole_waits_the_probe_wait_is_capped() {
        var cap = SETTINGS.holeProbeWaitMax();
        assertThat(cap).isEqualTo(Duration.ofMillis(100));
        // 5 ms x 100 x (60 / 10) = 3 s uncapped, woken or not
        assertThat(ReaderLoop.probeWait(SETTINGS, true, HONEST_PROBE, POOL_60, true, TestSeams.NONE)).isLessThanOrEqualTo(cap);
        assertThat(ReaderLoop.probeWait(SETTINGS, false, HONEST_PROBE, POOL_60, true, TestSeams.NONE)).isLessThanOrEqualTo(cap);
        // a woken wait already below the cap is kept
        assertThat(ReaderLoop.probeWait(SETTINGS, true, 0, 1, true, TestSeams.NONE)).isEqualTo(SETTINGS.wokenProbeMin());
        var custom = SETTINGS.withHoleProbeWaitMax(Duration.ofMillis(40));
        assertThat(ReaderLoop.probeWait(custom, false, HONEST_PROBE, POOL_60, true, TestSeams.NONE)).isEqualTo(Duration.ofMillis(40));
    }

    @Test
    void without_a_waiting_hole_the_cost_rule_is_unchanged() {
        assertThat(ReaderLoop.probeWait(SETTINGS, true, HONEST_PROBE, POOL_60, false, TestSeams.NONE)).isEqualTo(SETTINGS.wokenProbeWait(HONEST_PROBE, POOL_60))
                                                                                                    .isEqualTo(Duration.ofSeconds(3));
        assertThat(ReaderLoop.probeWait(SETTINGS, false, HONEST_PROBE, POOL_60, false, TestSeams.NONE)).isEqualTo(SETTINGS.probeWait(HONEST_PROBE, POOL_60));
    }

    @Test
    void must_fail_HOLE_PROBE_WAIT_UNCAPPED_holds_the_hole_for_seconds() {
        var seams = new TestSeams(Set.of(WatermarkMutant.HOLE_PROBE_WAIT_UNCAPPED), TestSeams.Hooks.NONE, null);
        assertThat(ReaderLoop.probeWait(SETTINGS, true, HONEST_PROBE, POOL_60, true, seams)).isGreaterThanOrEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void hole_probe_wait_max_must_be_positive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SETTINGS.withHoleProbeWaitMax(Duration.ZERO))
                                       .isInstanceOf(IllegalArgumentException.class);
    }
}
