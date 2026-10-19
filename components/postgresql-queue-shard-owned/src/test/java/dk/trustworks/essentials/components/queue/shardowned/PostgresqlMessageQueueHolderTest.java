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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue.Holder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.*;

import static dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue.Holder.*;
import static org.assertj.core.api.Assertions.*;

/**
 * The queue's lifecycle {@link Holder} moves only along the edges of the diagram in {@code Holder}'s javadoc, and the
 * rule lives once, in {@link PostgresqlMessageQueue#moveHolder}: any other move leaves the holder where it stands, and is
 * logged at ERROR, as the bug in the queue's lifecycle it is; a move to where the holder stands is no move and says
 * nothing. A stop arriving while the holder does the stop it owed is owed again - this queue's stop without the lock
 * stops every consumer itself, and the holder stops them all again after it - where the adapter's consumer owes nothing
 * further.
 */
class PostgresqlMessageQueueHolderTest {

    private ListAppender<ILoggingEvent>   logged;
    private ch.qos.logback.classic.Logger queueLogger;

    @BeforeEach
    void captureLog() {
        logged = new ListAppender<>();
        logged.start();
        queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        queueLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        queueLogger.detachAppender(logged);
    }

    private static final Map<Holder, Set<Holder>> EDGES = Map.of(
            NONE, EnumSet.of(STARTING, STOPPING),
            STARTING, EnumSet.of(UNDOING_A_START, STOP_OWED, NONE),
            UNDOING_A_START, EnumSet.of(STARTING, STOP_OWED),
            STOP_OWED, EnumSet.of(FINISHING_OWED_STOP, NONE),
            FINISHING_OWED_STOP, EnumSet.of(STOP_OWED, NONE),
            STOPPING, EnumSet.of(NONE));

    /**
     * How each holder is reached from {@link Holder#NONE}, along the diagram.
     */
    private static final Map<Holder, List<Holder>> PATH_FROM_NONE = Map.of(
            NONE, List.of(),
            STARTING, List.of(STARTING),
            UNDOING_A_START, List.of(STARTING, UNDOING_A_START),
            STOP_OWED, List.of(STARTING, STOP_OWED),
            FINISHING_OWED_STOP, List.of(STARTING, STOP_OWED, FINISHING_OWED_STOP),
            STOPPING, List.of(STOPPING));

    @ParameterizedTest
    @EnumSource(Holder.class)
    void the_holder_moves_only_along_the_diagrams_edges_and_any_other_move_is_logged_at_error(Holder start) {
        for (var target : Holder.values()) {
            var queue = queueHeldBy(start);
            logged.list.clear();

            queue.moveHolder(target);

            var onTheDiagram = EDGES.get(start).contains(target);
            assertThat(queue.holder()).as("%s asked for %s", start, target)
                                      .isEqualTo(onTheDiagram ? target : start);
            assertThat(start.canMoveTo(target)).as("%s.canMoveTo(%s)", start, target).isEqualTo(onTheDiagram);
            if (onTheDiagram || target == start) {
                assertThat(logged.list).as("%s asked for %s", start, target).isEmpty();
            } else {
                assertThat(logged.list).as("%s asked for %s", start, target).singleElement().satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("holder-test", "from " + start + " to " + target);
                    assertThat(event.getThrowableProxy()).as("the stack naming the caller").isNotNull();
                });
            }
        }
    }

    @Test
    void an_off_diagram_move_from_a_start_to_a_stop_is_logged_at_error() {
        var queue = queueHeldBy(STARTING);
        logged.list.clear();

        queue.moveHolder(STOPPING);

        assertThat(queue.holder()).isEqualTo(STARTING);
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("from STARTING to STOPPING");
        });
    }

    /**
     * A second stop that does not get the lock, or a second on the holder's own thread, asks for the {@link Holder#STOP_OWED}
     * the first left: no move, and nothing wrong.
     */
    @Test
    void a_second_owed_stop_is_no_move_and_logs_nothing() {
        var queue = queueHeldBy(STOP_OWED);
        logged.list.clear();

        queue.moveHolder(STOP_OWED);

        assertThat(queue.holder()).isEqualTo(STOP_OWED);
        assertThat(logged.list).isEmpty();
    }

    @Test
    void a_stop_arriving_while_the_holder_does_the_stop_it_owed_is_owed_again() {
        var queue = queueHeldBy(FINISHING_OWED_STOP);

        queue.moveHolder(STOP_OWED);

        assertThat(queue.holder()).isEqualTo(STOP_OWED);
    }

    @Test
    void a_stop_never_takes_over_from_a_start_or_another_stop() {
        for (var start : List.of(STARTING, UNDOING_A_START, STOP_OWED, FINISHING_OWED_STOP, STOPPING)) {
            var queue = queueHeldBy(start);

            queue.moveHolder(STOPPING);

            assertThat(queue.holder()).as("%s asked for STOPPING", start).isEqualTo(start);
        }
    }

    /**
     * A stop arriving while a failed start stops what it started is owed, as during the starts, and the end of that undo
     * does not take it back: there is no edge from {@link Holder#STOP_OWED} to {@link Holder#STARTING}, so the end of the
     * undo moves the holder back only from {@link Holder#UNDOING_A_START} ({@code startEachOrUndo}), and a move asked for
     * regardless is the bug it is logged as.
     */
    @Test
    void a_stop_arriving_while_a_failed_start_is_undone_stays_owed_once_the_undo_ends() {
        var queue = queueHeldBy(UNDOING_A_START);

        queue.moveHolder(STOP_OWED);
        queue.moveHolder(STARTING);

        assertThat(queue.holder()).isEqualTo(STOP_OWED);
        assertThat(logged.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage()).contains("from STOP_OWED to STARTING"));
    }

    /**
     * Nothing starts while a holder stops the queue or undoes a failed start of its own, and only those; a stop that does
     * not get the lock is owed by every kind of start, the undoing one included.
     */
    @Test
    void nothing_starts_while_the_holder_stops_the_queue_or_undoes_a_failed_start() {
        assertThat(EnumSet.allOf(Holder.class).stream().filter(Holder::letsNothingStart))
                .containsExactlyInAnyOrder(UNDOING_A_START, STOP_OWED, FINISHING_OWED_STOP, STOPPING);
        assertThat(EnumSet.allOf(Holder.class).stream().filter(Holder::stopsTheQueue))
                .containsExactlyInAnyOrder(STOP_OWED, FINISHING_OWED_STOP, STOPPING);
        assertThat(EnumSet.allOf(Holder.class).stream().filter(Holder::isAStart))
                .containsExactlyInAnyOrder(STARTING, UNDOING_A_START, STOP_OWED, FINISHING_OWED_STOP);
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

    // DataSource's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static PostgresqlMessageQueue queue() {
        // Nothing here touches the database: the holder is moved directly.
        var noDatabase = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                             (proxy, method, args) -> {
                                                                 throw new UnsupportedOperationException(method.getName());
                                                             });
        return new PostgresqlMessageQueue(noDatabase, (short) 1, 1, "holder-test");
    }
}
