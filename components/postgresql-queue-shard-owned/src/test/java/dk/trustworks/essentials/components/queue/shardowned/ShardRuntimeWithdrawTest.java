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

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link ShardRuntime#withdraw}: what a queue calls when the owner of a unit it has just placed cannot be built. It
 * forgets the wake-up the placement put in the runtime, and only that one - a wake-up the placement reused, or one a later
 * placement put there in its stead, is left. A wake-up left behind for a unit that joined no run stayed until the runtime
 * stopped; one forgotten under an owner still serving its unit would leave that owner's notifications reaching nobody.
 */
class ShardRuntimeWithdrawTest {
    private static final short  QUEUE_ID = 7;
    private static final String LANE     = "unordered";

    private ShardRuntime runtime;

    @BeforeEach
    void startRuntime() {
        runtime = new ShardRuntime(ShardPumpExitTest.listenableDataSource(), ShardOwnerSettings.defaults(), new ShardOwnerMetrics());
    }

    @AfterEach
    void stopRuntime() {
        runtime.stop();
    }

    @Test
    void withdrawing_a_placement_that_created_the_wake_up_forgets_it() {
        var placement = runtime.place(QUEUE_ID, LANE, 1);
        assertThat(placement.createdWakeup()).as("premise: the first placement of the unit creates its wake-up").isTrue();
        assertThat(runtime.wakeupsHeld()).isEqualTo(1);

        runtime.withdraw(placement);

        assertThat(runtime.wakeupsHeld()).as("the wake-up the placement created is forgotten").isZero();
    }

    @Test
    void withdrawing_a_placement_that_reused_the_wake_up_leaves_it() {
        var first  = runtime.place(QUEUE_ID, LANE, 1);
        var second = runtime.place(QUEUE_ID, LANE, 1);
        assertThat(second.createdWakeup()).as("premise: a second placement of the unit reuses its wake-up").isFalse();
        assertThat(second.wakeup()).isSameAs(first.wakeup());

        runtime.withdraw(second);

        assertThat(runtime.wakeupsHeld()).as("a wake-up that was there before the placement stays").isEqualTo(1);
        assertThat(runtime.place(QUEUE_ID, LANE, 1).wakeup()).as("the same wake-up").isSameAs(first.wakeup());
    }

    @Test
    void withdrawing_a_placement_leaves_a_wake_up_a_later_placement_put_in_its_stead() {
        var earlier = runtime.place(QUEUE_ID, LANE, 1);
        runtime.forget(QUEUE_ID, LANE, 1);
        var later = runtime.place(QUEUE_ID, LANE, 1);
        assertThat(later.createdWakeup()).as("premise: the later placement created a wake-up of its own").isTrue();

        runtime.withdraw(earlier);

        assertThat(runtime.wakeupsHeld()).as("the later placement's wake-up stays").isEqualTo(1);
        assertThat(runtime.place(QUEUE_ID, LANE, 1).wakeup()).isSameAs(later.wakeup());
    }

    @Test
    void withdrawing_one_unit_leaves_the_wake_ups_of_the_others() {
        runtime.place(QUEUE_ID, LANE, 0);
        var failed = runtime.place(QUEUE_ID, LANE, 1);

        runtime.withdraw(failed);

        assertThat(runtime.wakeupsHeld()).as("only unit 1's wake-up is forgotten").isEqualTo(1);
        assertThat(runtime.place(QUEUE_ID, LANE, 0).createdWakeup()).as("unit 0's wake-up is still there").isFalse();
    }
}
