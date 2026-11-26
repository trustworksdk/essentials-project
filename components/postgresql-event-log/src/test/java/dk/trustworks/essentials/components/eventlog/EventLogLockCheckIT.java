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

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The stuck candidate's lock check ({@link TailReader#LOCK_CHECK_AFTER}, docs/design/event-log.md §4). A transaction
 * that holds an xid but has not inserted into the type's event table cannot commit into one of its holes, so it holds
 * the watermark for about {@link TailReader#LOCK_CHECK_AFTER} at most; an append that drew a hole still holds it for as
 * long as it runs.
 */
class EventLogLockCheckIT extends AbstractSubscriptionIT {
    private static final Duration PAST_LOCK_CHECK = TailReader.LOCK_CHECK_AFTER.multipliedBy(3);

    @Test
    void a_long_transaction_that_wrote_another_table_holds_a_contiguous_subscriber_back_only_until_the_lock_check() throws Exception {
        execute("CREATE TABLE el_test_unrelated (n int)");
        var recorder     = new Recorder();
        var subscription = contiguous(recorder, TestSeams.NONE);
        awaitTrue(subscription::isIdle);

        // A batch job: it holds an xid (it wrote something) and stays open, but never touches the event table
        var unrelated = connection();
        unrelated.setAutoCommit(false);
        execute(unrelated, "INSERT INTO el_test_unrelated VALUES (1)");

        var rolledBack = openAppend("A", 1);
        rolledBack.rollback();
        var after = commitAppend("B", 1);

        await().atMost(PAST_LOCK_CHECK.plus(Duration.ofSeconds(5)))
               .untilAsserted(() -> assertThat(recorder.deliveredOrder(after)).as("delivered while the unrelated transaction is still open").isTrue());
        unrelated.rollback();
    }

    @Test
    void an_append_that_drew_a_hole_holds_a_contiguous_subscriber_back_for_as_long_as_it_runs() throws Exception {
        var recorder     = new Recorder();
        var subscription = contiguous(recorder, TestSeams.NONE);
        awaitTrue(subscription::isIdle);

        var writer = openAppend("A", 1);
        var after  = commitAppend("B", 1);
        await().during(PAST_LOCK_CHECK).atMost(PAST_LOCK_CHECK.plus(Duration.ofSeconds(5)))
               .until(() -> recorder.delivered.isEmpty());

        writer.commit();
        awaitDelivered(recorder, writer.globalOrder(), after);
        assertThat(recorder.delivered.stream().map(StoredEvent::globalOrder).toList()).containsExactly(writer.globalOrder(), after);
    }

    @Test
    void must_fail_lock_check_without_lockers_retires_the_hole_of_a_running_append_and_its_event_is_lost() throws Exception {
        var recorder     = new Recorder();
        var subscription = contiguous(recorder, new TestSeams(Set.of(WatermarkMutant.LOCK_CHECK_WITHOUT_LOCKERS), TestSeams.Hooks.NONE, null));
        awaitTrue(subscription::isIdle);

        var writer = openAppend("A", 1);
        var after  = commitAppend("B", 1);
        await().atMost(PAST_LOCK_CHECK.plus(Duration.ofSeconds(5)))
               .untilAsserted(() -> assertThat(recorder.deliveredOrder(after)).as("the mutant moves the watermark past the running append").isTrue());

        writer.commit();
        assertThat(deliveredWithin(recorder, writer.globalOrder())).as("the late commit lands below the watermark and is never delivered").isFalse();
    }

    private Subscription contiguous(Recorder recorder, TestSeams seams) throws Exception {
        return subscribe(Subscription.SubscriptionSpec.periodic(new ProgressStore.Key("contiguous", TYPE, "node-1"), 1, ConsistencyMode.CONTIGUOUS, recorder, seams));
    }

    private static void execute(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
