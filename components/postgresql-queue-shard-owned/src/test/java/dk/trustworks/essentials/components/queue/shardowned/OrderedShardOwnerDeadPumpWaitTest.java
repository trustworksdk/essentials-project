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

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An ordered unit on a dead pump waits for its keys still in a handler, and {@link OrderedShardOwner#stuckOnDeadPump()}
 * reports that wait once it outlasts {@code shedGrace}. The wait starts when the pump died, which is when it ended the
 * owner ({@link OrderedShardOwner#beginDraining()}), not when the queue's heartbeat first asked: timed from the first ask,
 * the WARN understated the wait by up to one heartbeat interval and fired that much later than {@code shedGrace} after the
 * death, so it did not add up against the pump's death ERROR.
 */
class OrderedShardOwnerDeadPumpWaitTest {

    private static final Duration SHED_GRACE = Duration.ofMillis(100);

    @Test
    void the_wait_is_timed_from_the_death_not_from_the_first_heartbeat_that_asks() throws Exception {
        var owner = owner();
        keysInFlight(owner).put("HOLD", new OrderedShardOwner.InHandler(1L, System.nanoTime()));

        // The pump ends the owner as it dies.
        owner.beginDraining();
        var afterDeath = System.nanoTime();
        sleepAtLeast(afterDeath, SHED_GRACE);
        var sinceDeathBeforeAsking = Duration.ofNanos(System.nanoTime() - afterDeath);

        // The heartbeat asks for the first time only now, a whole grace after the death.
        var stuck = owner.stuckOnDeadPump();

        assertThat(stuck).as("the grace has passed since the death, so the first heartbeat to ask reports it").isPresent();
        assertThat(stuck.get().keys()).containsExactly("HOLD");
        assertThat(stuck.get().waited()).as("counted from the death, not from this first ask").isGreaterThanOrEqualTo(sinceDeathBeforeAsking);
        assertThat(owner.stuckOnDeadPump()).as("reported once per owner").isEmpty();
    }

    @Test
    void within_the_grace_since_the_death_nothing_is_reported() throws Exception {
        var owner = owner();
        keysInFlight(owner).put("HOLD", new OrderedShardOwner.InHandler(1L, System.nanoTime()));

        owner.beginDraining();

        assertThat(owner.stuckOnDeadPump()).as("asked straight after the death, well within the grace").isEmpty();
    }

    @Test
    void an_owner_that_never_began_draining_is_timed_from_the_first_ask() throws Exception {
        var owner = owner();
        keysInFlight(owner).put("HOLD", new OrderedShardOwner.InHandler(1L, System.nanoTime()));

        var beforeFirstAsk = System.nanoTime();
        assertThat(owner.stuckOnDeadPump()).as("the first ask starts the clock").isEmpty();
        sleepAtLeast(System.nanoTime(), SHED_GRACE);

        var stuck = owner.stuckOnDeadPump();

        assertThat(stuck).isPresent();
        assertThat(stuck.get().waited()).isGreaterThanOrEqualTo(SHED_GRACE)
                                        .isLessThanOrEqualTo(Duration.ofNanos(System.nanoTime() - beforeFirstAsk));
    }

    private static OrderedShardOwner owner() {
        var settings = ShardOwnerSettings.builder().setShedGrace(SHED_GRACE).build();
        // Nothing here touches the database: stuckOnDeadPump and beginDraining are in-memory.
        var noDatabase = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                             (proxy, method, args) -> {
                                                                 throw new UnsupportedOperationException(method.getName());
                                                             });
        return new OrderedShardOwner(new ShardOwnedStorage(noDatabase, (short) 1), 3, 1L, settings, (id, key, payload, type) -> { },
                                     new ShardOwnerMetrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3), "instance-under-test",
                                     new ShardWakeup(), HandlerDispatch.inline(), false);
    }

    /**
     * The owner's keys in a handler, each with the message it is in its handler with and since when. Set directly: a handler
     * on the shared executor outliving its dead pump is what holds the hand-back up, and reaching it through dispatch would
     * need a database read.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, OrderedShardOwner.InHandler> keysInFlight(OrderedShardOwner owner) throws Exception {
        var field = OrderedShardOwner.class.getDeclaredField("keysInFlight");
        field.setAccessible(true);
        return (Map<String, OrderedShardOwner.InHandler>) field.get(owner);
    }

    private static void sleepAtLeast(long sinceNanos, Duration duration) {
        while (System.nanoTime() - sinceNanos < duration.toNanos()) {
            LockSupport.parkNanos(duration.toNanos());
        }
    }
}
