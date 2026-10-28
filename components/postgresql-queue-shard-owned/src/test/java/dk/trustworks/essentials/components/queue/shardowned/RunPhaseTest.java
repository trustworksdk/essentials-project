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

import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedQueue.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.*;

import static dk.trustworks.essentials.components.queue.shardowned.ShardOwnedQueue.Phase.*;
import static org.assertj.core.api.Assertions.*;

/**
 * A {@link Run}'s {@link Phase} moves only along the edges of the diagram in {@code Phase}'s javadoc, and the rule lives
 * once, in {@link Run#advanceTo}: {@code STARTING} goes to any other phase, every other phase but {@code ENDED} goes to
 * {@code ENDED} alone, and nothing leaves {@code ENDED}. Any other move leaves the run where it stands - a started run
 * asked into a stop-owed phase would hand a stop's work to a start that is no longer running.
 */
class RunPhaseTest {

    private static final Map<Phase, Set<Phase>> EDGES = Map.of(
            STARTING, EnumSet.of(STARTED, START_ABORTED, STOP_OWED_GRACEFUL, STOP_OWED_ABRUPT, ENDED),
            STARTED, EnumSet.of(ENDED),
            START_ABORTED, EnumSet.of(ENDED),
            STOP_OWED_GRACEFUL, EnumSet.of(ENDED),
            STOP_OWED_ABRUPT, EnumSet.of(ENDED),
            ENDED, EnumSet.noneOf(Phase.class));

    @ParameterizedTest
    @EnumSource(Phase.class)
    void a_run_moves_only_along_the_diagrams_edges_and_reports_where_it_was(Phase start) {
        for (var target : Phase.values()) {
            var run = runAt(start);

            synchronized (run) {
                var was = run.advanceTo(target);

                assertThat(was).isEqualTo(start);
                assertThat(run.phase()).as("%s asked for %s", start, target)
                                       .isEqualTo(EDGES.get(start).contains(target) ? target : start);
            }
        }
    }

    @Test
    void a_started_run_is_never_moved_into_a_stop_owed_or_aborted_phase() {
        for (var target : List.of(START_ABORTED, STOP_OWED_GRACEFUL, STOP_OWED_ABRUPT)) {
            var run = runAt(STARTED);
            synchronized (run) {
                run.advanceTo(target);
                assertThat(run.phase()).as("STARTED asked for %s", target).isEqualTo(STARTED);
                assertThat(run.owedStop()).isNull();
            }
        }
    }

    @Test
    void a_stop_owed_gracefully_is_never_turned_into_an_abrupt_one() {
        var run = runAt(STOP_OWED_GRACEFUL);
        synchronized (run) {
            var owedBefore = run.owedStop();
            assertThat(owedBefore).as("set-up").isNotNull();

            run.advanceTo(STOP_OWED_ABRUPT);

            assertThat(run.phase()).isEqualTo(STOP_OWED_GRACEFUL);
            assertThat(run.owedStop()).isSameAs(owedBefore);
        }
    }

    @Test
    void a_new_run_is_starting() {
        var run = new Run();
        synchronized (run) {
            assertThat(run.phase()).isEqualTo(STARTING);
        }
    }

    @Test
    void the_diagram_names_every_phase() {
        assertThat(EDGES.keySet()).containsExactlyInAnyOrder(Phase.values());
    }

    private static Run runAt(Phase phase) {
        var run = new Run();
        synchronized (run) {
            if (phase != STARTING) {
                run.advanceTo(phase);
            }
            assertThat(run.phase()).as("set-up").isEqualTo(phase);
        }
        return run;
    }
}
