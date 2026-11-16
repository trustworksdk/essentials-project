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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I3, plan §16: <i>"A resume point never passes an order that could still be delivered"</i>.
 * <p>
 * A crash - the reader's backend terminated and the subscription stopped without a final save - between delivery
 * and handling, behind a long writer, between a candidate's retirement and its hole read, and while a restarted
 * reader's watermark is still unknown. After each, a new incarnation must deliver every event the first did not
 * handle. Must-fail variants (P0.5 mutants): resume point at the highest handled order or at the cursor, the watermark
 * moved before the hole read (F2), and catch-up taken as final above the unknown watermark (F4).
 */
class EventLogResumePointIT extends AbstractSubscriptionIT {
    @Test
    void a_crash_between_delivery_and_handling_redelivers_the_unhandled_events() throws Exception {
        assertThat(crashBetweenDeliveryAndHandling()).isEmpty();
    }

    @Test
    void must_fail_resume_point_at_the_cursor_loses_delivered_but_unhandled_events() throws Exception {
        assertThat(crashBetweenDeliveryAndHandling(WatermarkMutant.RESUME_AT_CURSOR)).isNotEmpty();
    }

    private Set<Long> crashBetweenDeliveryAndHandling(WatermarkMutant... mutants) throws Exception {
        var first = commitAppend("A", 5);
        var r1    = new Recorder();
        r1.blockAt = first + 2;
        var s1 = subscribe("sub", r1, mutants);
        assertThat(r1.blocked.await(30, TimeUnit.SECONDS)).isTrue();
        crash(s1);
        return lostAfterRestart(r1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = WatermarkMutant.class, names = {"RESUME_AT_HIGHEST_HANDLED", "RESUME_AT_CURSOR"})
    void must_fail_a_resume_point_past_a_long_writers_hole_loses_its_event(WatermarkMutant mutant) throws Exception {
        assertThat(crashBehindALongWriter(mutant)).isNotEmpty();
    }

    @Test
    void the_resume_point_stays_below_a_long_writers_hole() throws Exception {
        assertThat(crashBehindALongWriter()).isEmpty();
    }

    private Set<Long> crashBehindALongWriter(WatermarkMutant... mutants) throws Exception {
        var r1 = new Recorder();
        var s1 = subscribe("sub", r1, mutants);
        awaitDelivered(r1, commitAppend("pre", 1));
        var held  = openAppend("held", 1);
        var above = commitAppend("above", 3);
        awaitDelivered(r1, above, above + 1, above + 2);
        awaitReads(3);
        crash(s1);
        held.commit();
        return lostAfterRestart(r1);
    }

    @Test
    void a_crash_between_retirement_and_hole_read_redelivers_the_late_commit() throws Exception {
        assertThat(crashBetweenRetirementAndHoleRead()).isEmpty();
    }

    @Test
    void must_fail_the_watermark_moved_before_the_hole_read_loses_the_late_commit() throws Exception {
        assertThat(crashBetweenRetirementAndHoleRead(WatermarkMutant.WATERMARK_BEFORE_HOLE_READ)).isNotEmpty();
    }

    /**
     * The retiring probe is held inside its scan (after its snapshot) while the hole's writer commits, so the probe
     * cannot see the late row, yet its running set no longer holds the writer: the candidate retires and only the hole
     * read can deliver the row. The loop is held in that window until the subscription has worked through (and saved)
     * everything it received, then the subscription crashes. Phase 3: the save runs on the subscription's own thread,
     * and the mutant offers the moved watermark to the inbox before the hole read; the shared loop is released after
     * the crash so the next incarnation has a reader.
     */
    private Set<Long> crashBetweenRetirementAndHoleRead(WatermarkMutant... mutants) throws Exception {
        var r1          = new Recorder();
        var crashWindow = new CountDownLatch(1);
        var release     = new CountDownLatch(1);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeHoleRead(Collection<Long> holes) throws Exception {
                crashWindow.countDown();
                release.await();
            }
        };
        var s1 = subscribe("sub", r1, new TestSeams(Set.of(mutants), hooks, HELD));
        awaitDelivered(r1, commitAppend("pre", 1));
        var held = openAppend("held", 1);
        awaitDelivered(r1, commitAppend("above", 1));
        awaitTrue(() -> reads.lastCandidates >= 1);
        var holder = armScanHold();
        commitAppend("above", 1);
        awaitScanBlocked();
        held.commit();
        releaseScan(holder);
        assertThat(crashWindow.await(30, TimeUnit.SECONDS)).as("the candidate retired and the hole read is next").isTrue();
        awaitTrue(s1::isIdle);
        crash(s1);
        release.countDown();
        return lostAfterRestart(r1);
    }

    @Test
    void a_save_while_the_loop_holds_a_read_before_publishing_it_redelivers_that_read() throws Exception {
        assertThat(crashWhileTheLoopHoldsARead()).isEmpty();
    }

    @Test
    void must_fail_a_resume_point_from_the_readers_live_watermark_loses_the_unpublished_read() throws Exception {
        assertThat(crashWhileTheLoopHoldsARead(WatermarkMutant.RESUME_FROM_READER_WATERMARK)).isNotEmpty();
    }

    /**
     * Phase 3 §3.2: the handler blocks on {@code g1}; the loop reads {@code g2} - the reader's state already has the
     * moved watermark - and is held at {@code afterReadBeforePublish}. The handler is released, the subscription saves
     * on its own thread, and crashes before the loop offers {@code g2}.
     */
    private Set<Long> crashWhileTheLoopHoldsARead(WatermarkMutant... mutants) throws Exception {
        var r1      = new Recorder();
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void afterReadBeforePublish(String aggregateType, List<StoredEvent> rows) throws Exception {
                if (rows.stream().anyMatch(e -> e.aggregateId().equals("second")) && holding.getCount() > 0) {
                    holding.countDown();
                    release.await(30, TimeUnit.SECONDS);
                }
            }
        };
        r1.blockAtAggregate = "first";
        var s1 = subscribe("sub", r1, new TestSeams(Set.of(mutants), hooks, null));
        var g1 = commitAppend("first", 1);
        assertThat(r1.blocked.await(30, TimeUnit.SECONDS)).isTrue();
        var g2 = commitAppend("second", 1);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(eventLog.reader(TYPE).state().watermark()).as("the reader's state moved before the publish").isEqualTo(g2);
        r1.release.countDown();
        awaitTrue(() -> s1.lastSaved() >= g1 + 1);
        crash(s1);
        release.countDown();
        return lostAfterRestart(r1);
    }

    @Test
    void a_restart_keeps_the_resume_point_while_its_watermark_is_unknown() throws Exception {
        assertThat(crashWhileTheRestartWatermarkIsUnknown()).isEmpty();
    }

    @Test
    void must_fail_catch_up_taken_as_final_above_the_unknown_watermark_loses_the_held_value() throws Exception {
        assertThat(crashWhileTheRestartWatermarkIsUnknown(WatermarkMutant.CATCH_UP_ABOVE_WATERMARK)).isNotEmpty();
    }

    private Set<Long> crashWhileTheRestartWatermarkIsUnknown(WatermarkMutant... mutants) throws Exception {
        var r1 = new Recorder();
        var s1 = subscribe("sub", r1);
        awaitDelivered(r1, commitAppend("pre", 1));
        var held  = openAppend("held", 1);
        var above = commitAppend("above", 2);
        awaitDelivered(r1, above, above + 1);
        awaitTrue(() -> unchecked(() -> savedResumeFrom("sub")) == held.globalOrder());
        crash(s1);
        // Restarted while the writer still runs: the initial candidate covers the head, the watermark is unknown
        // Phase 3: the subscription waits for the watermark and reads nothing; the mutant catches up above it
        var r2 = new Recorder();
        var s2 = subscribe("sub", r2, mutants);
        awaitTrue(s2::isIdle);
        awaitReads(3);
        awaitTrue(s2::isIdle);
        crash(s2);
        held.commit();
        var handled = new HashSet<StoredEvent>(r1.handled);
        handled.addAll(r2.handled);
        return lostAfterRestart(handled);
    }

    private void crash(FreshSubscription subscription) throws SQLException {
        terminateReaderBackend();
        subscription.crash();
    }

    private Set<Long> lostAfterRestart(Recorder first) throws Exception {
        return lostAfterRestart(new HashSet<>(first.handled));
    }

    /**
     * Starts a correct incarnation from the saved resume point and returns every committed order that neither the
     * earlier incarnations handled nor this one delivered, once it has delivered an event appended after the restart
     */
    private Set<Long> lostAfterRestart(Collection<StoredEvent> handledBefore) throws Exception {
        var saved = savedResumeFrom("sub");
        var next  = new Recorder();
        subscribe("sub", next);
        awaitDelivered(next, commitAppend("tail", 1));
        awaitReads(3);
        var lost = new TreeSet<Long>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT global_order FROM " + tables.eventTable())) {
            while (resultSet.next()) {
                lost.add(resultSet.getLong(1));
            }
        }
        handledBefore.forEach(e -> lost.remove(e.globalOrder()));
        lost.removeAll(next.deliveredOrders());
        if (lost.isEmpty()) {
            assertThat(next.deliveredOrders()).as("every event at or above the saved point %d is redelivered", saved)
                                              .containsAll(new TreeSet<>(committedFrom(saved)));
        }
        return lost;
    }

    private Set<Long> committedFrom(long saved) throws SQLException {
        var orders = new TreeSet<Long>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT global_order FROM " + tables.eventTable() + " WHERE global_order >= " + saved)) {
            while (resultSet.next()) {
                orders.add(resultSet.getLong(1));
            }
        }
        return orders;
    }

    @FunctionalInterface
    private interface SqlSupplier {
        long get() throws Exception;
    }

    private static long unchecked(SqlSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
