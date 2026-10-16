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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 */
class LeasedOwnerLeaseRowTest {

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

    private static LeasedOwner owner(String lane) {
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
                                         "instance-under-test", new ShardWakeup(), HandlerDispatch.inline(), false);
        }
        return new ShardOwner(storage, 3, 1L, settings, (id, payload, type) -> { }, new ShardOwnerMetrics(), policy, new ShardWakeup(),
                              "instance-under-test", HandlerDispatch.inline(), false);
    }
}
