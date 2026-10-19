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

import dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue.Holder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.*;

import static dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue.Holder.*;
import static org.assertj.core.api.Assertions.*;

/**
 * The queue's lifecycle {@link Holder} moves only along the edges of the diagram in {@code Holder}'s javadoc, and the
 * rule lives once, in {@link PostgresqlMessageQueue#moveHolder}: any other move leaves the holder where it stands. A
 * stop arriving while the holder does the stop it owed is owed again - this queue's stop without the lock stops every
 * consumer itself, and the holder stops them all again after it - where the adapter's consumer owes nothing further.
 */
class PostgresqlMessageQueueHolderTest {

    private static final Map<Holder, Set<Holder>> EDGES = Map.of(
            NONE, EnumSet.of(STARTING, STOPPING),
            STARTING, EnumSet.of(STOP_OWED, NONE),
            STOP_OWED, EnumSet.of(FINISHING_OWED_STOP, NONE),
            FINISHING_OWED_STOP, EnumSet.of(STOP_OWED, NONE),
            STOPPING, EnumSet.of(NONE));

    /**
     * How each holder is reached from {@link Holder#NONE}, along the diagram.
     */
    private static final Map<Holder, List<Holder>> PATH_FROM_NONE = Map.of(
            NONE, List.of(),
            STARTING, List.of(STARTING),
            STOP_OWED, List.of(STARTING, STOP_OWED),
            FINISHING_OWED_STOP, List.of(STARTING, STOP_OWED, FINISHING_OWED_STOP),
            STOPPING, List.of(STOPPING));

    @ParameterizedTest
    @EnumSource(Holder.class)
    void the_holder_moves_only_along_the_diagrams_edges_and_reports_where_it_was(Holder start) {
        for (var target : Holder.values()) {
            var queue = queueHeldBy(start);

            var was = queue.moveHolder(target);

            assertThat(was).isEqualTo(start);
            assertThat(queue.holder()).as("%s asked for %s", start, target)
                                      .isEqualTo(EDGES.get(start).contains(target) ? target : start);
            assertThat(start.canMoveTo(target)).as("%s.canMoveTo(%s)", start, target).isEqualTo(EDGES.get(start).contains(target));
        }
    }

    @Test
    void a_stop_arriving_while_the_holder_does_the_stop_it_owed_is_owed_again() {
        var queue = queueHeldBy(FINISHING_OWED_STOP);

        queue.moveHolder(STOP_OWED);

        assertThat(queue.holder()).isEqualTo(STOP_OWED);
    }

    @Test
    void a_stop_never_takes_over_from_a_start_or_another_stop() {
        for (var start : List.of(STARTING, STOP_OWED, FINISHING_OWED_STOP, STOPPING)) {
            var queue = queueHeldBy(start);

            queue.moveHolder(STOPPING);

            assertThat(queue.holder()).as("%s asked for STOPPING", start).isEqualTo(start);
        }
    }

    @Test
    void a_new_queue_is_held_by_none() {
        assertThat(queue().holder()).isEqualTo(NONE);
    }

    @Test
    void the_diagram_names_every_holder() {
        assertThat(EDGES.keySet()).containsExactlyInAnyOrder(Holder.values());
        assertThat(PATH_FROM_NONE.keySet()).containsExactlyInAnyOrder(Holder.values());
    }

    private static PostgresqlMessageQueue queueHeldBy(Holder holder) {
        var queue = queue();
        for (var step : PATH_FROM_NONE.get(holder)) {
            queue.moveHolder(step);
        }
        assertThat(queue.holder()).as("set-up").isEqualTo(holder);
        return queue;
    }

    private static PostgresqlMessageQueue queue() {
        // Nothing here touches the database: the holder is moved directly.
        var noDatabase = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                             (proxy, method, args) -> {
                                                                 throw new UnsupportedOperationException(method.getName());
                                                             });
        return new PostgresqlMessageQueue(noDatabase, (short) 1, 1, "holder-test");
    }
}
