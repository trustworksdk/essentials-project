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

import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedQueue.KeptUnit;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedQueue.KeptUnit.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.util.*;

import static dk.trustworks.essentials.components.queue.shardowned.ShardOwnedQueue.KeptUnit.Phase.*;
import static org.assertj.core.api.Assertions.*;

/**
 * A {@link KeptUnit}'s {@link Phase} moves only along the edges of the diagram in {@code Phase}'s javadoc, and the rule
 * lives once, in {@link Phase#canAdvanceTo}: {@link KeptUnit#moveFrom} moves a unit only from where it stands and only
 * along an edge, and {@link KeptUnit#claimHandBack} only from a phase with an edge to {@code HANDING_BACK}. The edges that
 * matter most: nothing hands back a unit the stop's budget thread is still flushing ({@code STOP_FLUSHING}), and only a
 * review gives it up from there, once that flush has outlasted {@code stuckHandlerTimeout} - it may never return; and a
 * unit a hand-back is running for is neither claimed again (no edge from {@code HANDING_BACK} to itself) nor given up by a
 * review, which gives up only from {@code AWAITING_HAND_BACK} and {@code STOP_FLUSHING}: the edges back out of
 * {@code HANDING_BACK} are the claiming hand-back's alone ({@link KeptUnit#endHandBack}).
 */
class KeptUnitPhaseTest {

    private static final Map<Phase, Set<Phase>> EDGES = Map.of(
            STOP_FLUSHING, EnumSet.of(AWAITING_HAND_BACK, GIVEN_UP),
            AWAITING_HAND_BACK, EnumSet.of(HANDING_BACK, GIVEN_UP),
            HANDING_BACK, EnumSet.of(HANDED_BACK, FORCE_RELEASED, AWAITING_HAND_BACK, GIVEN_UP, TAKEOVER_ASKED),
            HANDED_BACK, EnumSet.noneOf(Phase.class),
            FORCE_RELEASED, EnumSet.noneOf(Phase.class),
            GIVEN_UP, EnumSet.of(TAKEOVER_ASKED, HANDING_BACK),
            TAKEOVER_ASKED, EnumSet.of(HANDING_BACK));

    @ParameterizedTest
    @EnumSource(Phase.class)
    void a_kept_unit_moves_only_along_the_diagrams_edges(Phase start) {
        for (var target : Phase.values()) {
            var unit = unitAt(start);

            var moved = unit.moveFrom(start, target);

            var edge = EDGES.get(start).contains(target);
            assertThat(moved).as("%s asked for %s", start, target).isEqualTo(edge);
            assertThat(unit.phase()).as("%s asked for %s", start, target).isEqualTo(edge ? target : start);
        }
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void a_kept_unit_is_not_moved_from_a_phase_it_does_not_stand_in(Phase start) {
        for (var from : Phase.values()) {
            if (from == start) {
                continue;
            }
            for (var target : Phase.values()) {
                var unit = unitAt(start);

                assertThat(unit.moveFrom(from, target)).as("standing at %s, asked from %s to %s", start, from, target).isFalse();
                assertThat(unit.phase()).isEqualTo(start);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void a_hand_back_is_claimed_only_from_a_phase_with_an_edge_to_handing_back_and_reports_where_it_was(Phase start) {
        var unit = unitAt(start);

        var claimedFrom = unit.claimHandBack();

        if (EDGES.get(start).contains(HANDING_BACK)) {
            assertThat(claimedFrom).isEqualTo(start);
            assertThat(unit.phase()).isEqualTo(HANDING_BACK);
        } else {
            assertThat(claimedFrom).as("no hand-back claimed from %s", start).isNull();
            assertThat(unit.phase()).isEqualTo(start);
        }
    }

    @Test
    void a_unit_the_stops_budget_thread_is_still_flushing_is_not_handed_back() {
        var unit = new KeptUnit(owner());
        assertThat(unit.phase()).as("a new kept unit").isEqualTo(STOP_FLUSHING);

        assertThat(unit.claimHandBack()).isNull();
        assertThat(unit.isGivenUp()).isFalse();

        assertThat(unit.moveFrom(STOP_FLUSHING, AWAITING_HAND_BACK)).as("the budget thread has flushed it").isTrue();
        assertThat(unit.claimHandBack()).isEqualTo(AWAITING_HAND_BACK);
    }

    /**
     * The stop's budget thread lets a unit out of {@code STOP_FLUSHING} only once its flush returns, which a connection
     * hung with no socket timeout may never do: a review gives the unit up from there once it has been kept longer than
     * {@code stuckHandlerTimeout} ({@link KeptUnit#keptFor}), and the budget thread's own move out of that phase, should its
     * flush return after all, then does nothing. Given up, the unit is handed back as any given up on is, its hand-back
     * claimed from {@code GIVEN_UP}.
     */
    @Test
    void a_unit_whose_stop_flush_does_not_return_is_given_up_and_the_late_flush_moves_it_nowhere() throws InterruptedException {
        var unit = new KeptUnit(owner());
        var before = unit.keptFor();
        Thread.sleep(5);
        assertThat(unit.keptFor()).as("timed from when the stop kept it").isGreaterThan(before).isPositive();

        assertThat(unit.moveFrom(STOP_FLUSHING, GIVEN_UP)).as("a review past the bound").isTrue();
        assertThat(unit.isGivenUp()).isTrue();
        assertThat(unit.nothingWaitsForHandler()).isTrue();

        assertThat(unit.moveFrom(STOP_FLUSHING, AWAITING_HAND_BACK)).as("the budget thread's flush returning late").isFalse();
        assertThat(unit.phase()).isEqualTo(GIVEN_UP);
        assertThat(unit.claimHandBack()).as("the hand-back that thread registers").isEqualTo(GIVEN_UP);
    }

    @Test
    void a_unit_a_hand_back_runs_for_is_neither_claimed_again_nor_given_up() {
        var unit = unitAt(AWAITING_HAND_BACK);
        assertThat(unit.claimHandBack()).isEqualTo(AWAITING_HAND_BACK);

        assertThat(unit.claimHandBack()).as("a second hand-back").isNull();
        assertThat(unit.moveFrom(AWAITING_HAND_BACK, GIVEN_UP)).as("a review giving it up").isFalse();
        assertThat(unit.moveFrom(STOP_FLUSHING, GIVEN_UP)).as("a review giving it up past the stop's flush").isFalse();
        assertThat(unit.phase()).isEqualTo(HANDING_BACK);
        assertThat(unit.isGivenUp()).isFalse();
    }

    @Test
    void a_hand_back_that_leaves_the_row_held_goes_back_where_it_was_claimed_from_and_one_that_does_not_ends_it() {
        for (var claimedFrom : List.of(AWAITING_HAND_BACK, GIVEN_UP, TAKEOVER_ASKED)) {
            var failed = unitAt(claimedFrom);
            assertThat(failed.claimHandBack()).isEqualTo(claimedFrom);
            failed.endHandBack(claimedFrom, true);
            assertThat(failed.phase()).as("claimed from %s, row still held", claimedFrom).isEqualTo(claimedFrom);
            assertThat(failed.isGivenUp()).isEqualTo(claimedFrom.isGivenUp());

            var done = unitAt(claimedFrom);
            assertThat(done.claimHandBack()).isEqualTo(claimedFrom);
            done.endHandBack(claimedFrom, false);
            assertThat(done.phase()).as("claimed from %s, row no longer held", claimedFrom).isEqualTo(HANDED_BACK);
            assertThat(done.claimHandBack()).as("nothing left to hand back").isNull();
        }
    }

    @Test
    void a_takeover_is_asked_once_and_only_of_a_unit_given_up_on() {
        var awaiting = unitAt(AWAITING_HAND_BACK);
        assertThat(awaiting.moveFrom(GIVEN_UP, TAKEOVER_ASKED)).isFalse();
        assertThat(awaiting.phase()).isEqualTo(AWAITING_HAND_BACK);

        var givenUp = unitAt(GIVEN_UP);
        assertThat(givenUp.moveFrom(GIVEN_UP, TAKEOVER_ASKED)).isTrue();
        assertThat(givenUp.moveFrom(GIVEN_UP, TAKEOVER_ASKED)).as("asked once").isFalse();
        assertThat(givenUp.isGivenUp()).isTrue();
    }

    /**
     * A release on request ({@code ShardOwnedQueue.releaseKeptOrderedUnit}) ends where nothing waits for the handler any
     * more while a key is still in it - so neither a later stop nor a {@code PostgresqlMessageQueue} holding an ended
     * subscription's instance id waits for it - as an ordinary hand-back when none is, and back where it was claimed from
     * when the release left the row held. Whether a key is in its handler is the release's own reading, passed in, never
     * asked of the owner again (an owner answering nothing here): read again after the release, it could disagree with
     * the keys the release named in its log line and its answer.
     */
    @Test
    void a_release_on_request_ends_force_released_while_a_key_is_in_its_handler_and_handed_back_when_none_is() {
        for (var claimedFrom : List.of(AWAITING_HAND_BACK, GIVEN_UP, TAKEOVER_ASKED)) {
            var inHandler = unitAt(claimedFrom);
            assertThat(inHandler.claimHandBack()).isEqualTo(claimedFrom);
            inHandler.endForcedRelease(claimedFrom, false, true);
            assertThat(inHandler.phase()).as("claimed from %s, a key in its handler", claimedFrom).isEqualTo(FORCE_RELEASED);
            assertThat(inHandler.nothingWaitsForHandler()).isTrue();
            assertThat(inHandler.isGivenUp()).as("released, not given up on").isFalse();
            assertThat(inHandler.claimHandBack()).as("nothing left to hand back").isNull();

            var returned = unitAt(claimedFrom);
            assertThat(returned.claimHandBack()).isEqualTo(claimedFrom);
            returned.endForcedRelease(claimedFrom, false, false);
            assertThat(returned.phase()).as("claimed from %s, no key in its handler", claimedFrom).isEqualTo(HANDED_BACK);

            var failed = unitAt(claimedFrom);
            assertThat(failed.claimHandBack()).isEqualTo(claimedFrom);
            failed.endForcedRelease(claimedFrom, true, true);
            assertThat(failed.phase()).as("claimed from %s, row still held", claimedFrom).isEqualTo(claimedFrom);
        }
        assertThat(EnumSet.allOf(Phase.class)).filteredOn(Phase::nothingWaitsForHandler)
                                              .containsExactlyInAnyOrder(GIVEN_UP, TAKEOVER_ASKED, FORCE_RELEASED);
    }

    @Test
    void the_diagram_names_every_phase() {
        assertThat(EDGES.keySet()).containsExactlyInAnyOrder(Phase.values());
    }

    /**
     * A kept unit standing at {@code phase}, reached along the diagram's edges only.
     */
    private static KeptUnit unitAt(Phase phase) {
        return unitAt(phase, owner());
    }

    /**
     * A kept unit of {@code owner} standing at {@code phase}, reached along the diagram's edges only.
     */
    private static KeptUnit unitAt(Phase phase, LeasedOwner owner) {
        var unit = new KeptUnit(owner);
        var path = switch (phase) {
            case STOP_FLUSHING -> List.<Phase>of();
            case AWAITING_HAND_BACK -> List.of(AWAITING_HAND_BACK);
            case HANDING_BACK -> List.of(AWAITING_HAND_BACK, HANDING_BACK);
            case HANDED_BACK -> List.of(AWAITING_HAND_BACK, HANDING_BACK, HANDED_BACK);
            case FORCE_RELEASED -> List.of(AWAITING_HAND_BACK, HANDING_BACK, FORCE_RELEASED);
            case GIVEN_UP -> List.of(AWAITING_HAND_BACK, GIVEN_UP);
            case TAKEOVER_ASKED -> List.of(AWAITING_HAND_BACK, GIVEN_UP, TAKEOVER_ASKED);
        };
        for (var next : path) {
            assertThat(unit.moveFrom(unit.phase(), next)).as("set-up: to %s", next).isTrue();
        }
        assertThat(unit.phase()).as("set-up").isEqualTo(phase);
        return unit;
    }

    /**
     * An owner nothing here asks anything of: the phase is the unit's own, never read from its owner.
     */
    // LeasedOwner's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static LeasedOwner owner() {
        return (LeasedOwner) Proxy.newProxyInstance(LeasedOwner.class.getClassLoader(), new Class<?>[]{LeasedOwner.class},
                                                    (proxy, method, args) -> {
                                                        throw new UnsupportedOperationException(method.getName());
                                                    });
    }
}
