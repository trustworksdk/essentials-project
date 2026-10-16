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

import dk.trustworks.essentials.components.queue.shardowned.LeasedOwner.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where an owner's lease row stands is a field of the owner ({@link LeasedOwner#leaseRow()}), not something each site of
 * {@code ShardOwnedQueue} derives from the owner's lease flag, its pump and the queue's owner list. The pump's death, and a
 * stopping queue, end the owner in memory and keep the row ({@code ENDED_ROW_HELD}): it serves nothing, and still counts as
 * this instance's until its queue has released the row. Only the queue moves it on, to {@code RELEASED}, once the row is
 * released; a unit found taken goes there directly. The state never moves back.
 * <p>
 * The rule lives once, in {@link LeaseRowState}; the owner tests check that both owners delegate to it and that both
 * signal their wake-up when they leave {@code HELD} from outside their pump, so the pump drops them on its next pass.
 * <p>
 * Two transitions need the database and are not here. In both, the owner's own fenced write is refused by its fence and the
 * owner moves itself to {@code RELEASED} without going through {@link LeasedOwner#onLeaseEnded}: its acknowledgement flush
 * (from {@code HELD} or {@code ENDED_ROW_HELD}; {@code ShardOwnedFencingIT} pins it on both lanes), and its takeover
 * attempt charge ({@code leaseLostBeforeTakeover}; {@code ShardOwnedTakeoverAttemptsIT}'s
 * {@code ..._whose_lease_was_taken_before_its_takeover_charges_nothing} pin it on both lanes).
 */
class LeasedOwnerLeaseRowTest {

    @ParameterizedTest
    @EnumSource(LeaseRow.class)
    void the_state_only_ever_moves_forward_and_reports_where_it_was(LeaseRow start) {
        for (var target : LeaseRow.values()) {
            var state = stateAt(start);

            var was = state.advanceTo(target);

            assertThat(was).isEqualTo(start);
            assertThat(state.get()).as("%s asked for %s", start, target)
                                   .isEqualTo(target.ordinal() > start.ordinal() ? target : start);
        }
    }

    @Test
    void the_state_starts_held_and_may_skip_the_middle() {
        var state = new LeaseRowState();
        assertThat(state.get()).isEqualTo(LeaseRow.HELD);

        assertThat(state.advanceTo(LeaseRow.RELEASED)).isEqualTo(LeaseRow.HELD);
        assertThat(state.get()).isEqualTo(LeaseRow.RELEASED);
    }

    @Test
    void the_states_are_declared_in_the_order_they_are_reached() {
        // LeaseRowState compares ordinals: reordering the enum would silently reverse a transition.
        assertThat(LeaseRow.values()).containsExactly(LeaseRow.HELD, LeaseRow.ENDED_ROW_HELD, LeaseRow.RELEASED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void ending_an_owner_keeping_its_row_wakes_its_pump_once(String lane) {
        var wakeup = new ShardWakeup();
        var owner  = owner(lane, wakeup);

        owner.endKeepingLeaseRow();
        assertThat(wakeup.consume()).as("the pump drops the owner on its next pass, not at its backstop").isTrue();

        owner.endKeepingLeaseRow();
        owner.onLeaseEnded(LeaseEnd.RELEASED);
        assertThat(wakeup.consume()).as("only leaving HELD wakes the pump: the owner was already out of its selection").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_unit_found_taken_while_held_wakes_its_pump(String lane) {
        var wakeup = new ShardWakeup();
        var owner  = owner(lane, wakeup);

        owner.onLeaseEnded(LeaseEnd.TAKEN);

        assertThat(wakeup.consume()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_new_owner_holds_its_row_and_serves_its_unit(String lane) {
        var owner = owner(lane);

        assertThat(owner.leaseRow()).isEqualTo(LeaseRow.HELD);
        assertThat(owner.leaseHeld()).isTrue();
        assertThat(owner.holdsLeaseRow()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void an_owner_ended_keeping_its_row_serves_nothing_and_still_holds_the_row_until_the_queue_releases_it(String lane) {
        var owner = owner(lane);

        owner.endKeepingLeaseRow();

        assertThat(owner.leaseRow()).isEqualTo(LeaseRow.ENDED_ROW_HELD);
        assertThat(owner.leaseHeld()).as("serves nothing: not pumped, not shed, not in shardsHeld").isFalse();
        assertThat(owner.holdsLeaseRow()).as("still this instance's: counted, not dropped, not taken again").isTrue();

        owner.endKeepingLeaseRow();
        assertThat(owner.leaseRow()).as("ending it again changes nothing").isEqualTo(LeaseRow.ENDED_ROW_HELD);

        owner.onLeaseEnded(LeaseEnd.RELEASED);

        assertThat(owner.leaseRow()).isEqualTo(LeaseRow.RELEASED);
        assertThat(owner.leaseHeld()).isFalse();
        assertThat(owner.holdsLeaseRow()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void a_unit_found_taken_releases_the_row_at_once_and_nothing_moves_it_back(String lane) {
        var owner = owner(lane);

        owner.onLeaseEnded(LeaseEnd.TAKEN);

        assertThat(owner.leaseRow()).isEqualTo(LeaseRow.RELEASED);
        assertThat(owner.holdsLeaseRow()).isFalse();

        owner.endKeepingLeaseRow();
        owner.onLeaseEnded(LeaseEnd.RELEASED);
        assertThat(owner.leaseRow()).as("a released row is never held again").isEqualTo(LeaseRow.RELEASED);
    }

    private static LeaseRowState stateAt(LeaseRow start) {
        var state = new LeaseRowState();
        state.advanceTo(start);
        return state;
    }

    private static LeasedOwner owner(String lane) {
        return owner(lane, new ShardWakeup());
    }

    private static LeasedOwner owner(String lane, ShardWakeup wakeup) {
        // Nothing here touches the database: the lease row's state is in memory.
        var noDatabase = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                             (proxy, method, args) -> {
                                                                 throw new UnsupportedOperationException(method.getName());
                                                             });
        var storage  = new ShardOwnedStorage(noDatabase, (short) 1);
        var settings = ShardOwnerSettings.defaults();
        var policy   = RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3);
        if ("ordered".equals(lane)) {
            return new OrderedShardOwner(storage, 3, 1L, settings, (id, key, payload, type) -> { }, new ShardOwnerMetrics(), policy,
                                         "instance-under-test", wakeup, HandlerDispatch.inline(), false);
        }
        return new ShardOwner(storage, 3, 1L, settings, (id, payload, type) -> { }, new ShardOwnerMetrics(), policy, wakeup,
                              "instance-under-test", HandlerDispatch.inline(), false);
    }
}
