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
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I2: <i>"Per-aggregate order: within one subscription incarnation, A#n is first delivered before
 * A#n+1"</i>.
 * <p>
 * The unit-of-work scenario, and P0.5's F1 shape: A#n commits late into a hole, A#n+1 is appended after it and read
 * above the cursor, while a long writer elsewhere keeps the hole's candidate from retiring - with one and with several
 * events per transaction. Must-fail variant: a reader that delivers late commits only from the hole read at
 * retirement (F1, {@link WatermarkMutant#NO_HOLE_REREAD}).
 */
class EventLogPerAggregateOrderIT extends AbstractSubscriptionIT {
    @Test
    void the_section_4_5_unit_of_work_scenario_keeps_order() throws Exception {
        var recorder = new Recorder();
        subscribe("sub", recorder);
        var blocker = openAppend("blocker", 1);
        // T2 starts first and takes its xid before T_long appends A#0
        var t2 = connection();
        t2.setAutoCommit(false);
        assertThat(queryLong(t2, "SELECT pg_current_xact_id()::text::bigint")).isPositive();
        var aFirst = appendCommitted(eventLog, AppendRequest.afterEventOrder(TYPE, "A", AppendRequest.NEW_STREAM, events("E", 1)));
        var aNext  = eventLog.append(t2, AppendRequest.afterEventOrder(TYPE, "A", 0, events("E", 1)));
        t2.commit();
        assertThat(aNext.events().getFirst().globalOrder()).isGreaterThan(aFirst.getFirst().events().getFirst().globalOrder());
        awaitDelivered(recorder, aFirst.getFirst().events().getFirst().globalOrder(), aNext.events().getFirst().globalOrder());
        blocker.rollback();
        assertThat(perAggregateOrderViolations(recorder.delivered)).isEmpty();
    }

    @ParameterizedTest(name = "{0} events per transaction")
    @ValueSource(ints = {1, 3})
    void a_late_commit_into_a_hole_is_delivered_before_its_successor(int eventsPerTransaction) throws Exception {
        assertThat(lateCommitBehindALongWriter(eventsPerTransaction)).isEmpty();
    }

    @ParameterizedTest(name = "{0} events per transaction")
    @ValueSource(ints = {1, 3})
    void must_fail_without_the_hole_reread_the_successor_overtakes(int eventsPerTransaction) throws Exception {
        assertThat(lateCommitBehindALongWriter(eventsPerTransaction, WatermarkMutant.NO_HOLE_REREAD)).isNotEmpty();
    }

    /**
     * @return I2 violations seen
     */
    private List<String> lateCommitBehindALongWriter(int eventsPerTransaction, WatermarkMutant... mutants) throws Exception {
        var recorder = new Recorder();
        subscribe("sub", recorder, mutants);
        awaitDelivered(recorder, commitAppend("pre", 1));
        var longWriter = openAppend("elsewhere", 1);
        var aFirst     = openAppend(AppendRequest.afterEventOrder(TYPE, "A", AppendRequest.NEW_STREAM, events("E", eventsPerTransaction)));
        awaitDelivered(recorder, commitAppend("other", 1));
        await(() -> reads.lastCandidates >= 1);
        aFirst.commit();
        var aNext = appendCommitted(eventLog, AppendRequest.afterEventOrder(TYPE, "A", eventsPerTransaction - 1, events("E", eventsPerTransaction))).getFirst();
        awaitDelivered(recorder, aNext.events().getLast().globalOrder());
        // The long writer ends only now, so its candidate retires and the hole read runs
        longWriter.rollback();
        awaitDelivered(recorder, aFirst.result().events().stream().mapToLong(AppendedEvent::globalOrder).toArray());
        return perAggregateOrderViolations(recorder.delivered);
    }

    private void await(java.util.function.BooleanSupplier condition) {
        awaitTrue(condition);
    }
}
