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

import java.util.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consistency mode and progress model are independent strategies, so the two less common
 * pairs work too. One smoke test each: {@code FRESH} with in-transaction pages, and {@code CONTIGUOUS}
 * with a per-event handler and periodic saves.
 */
class EventLogModePairsIT extends AbstractPageIT {
    @Test
    void fresh_in_transaction_hands_on_above_a_held_value_and_applies_every_event_once() throws Exception {
        execute("CREATE TABLE el_test_seen (g bigint NOT NULL)");
        var delivered = Collections.synchronizedList(new ArrayList<StoredEvent>());
        PageHandler handler = PageHandler.perEvent((event, tx) -> {
            var connection = tx.connection();
            delivered.add(event);
            try (var statement = connection.prepareStatement("INSERT INTO el_test_seen VALUES (?)")) {
                statement.setLong(1, event.globalOrder());
                statement.executeUpdate();
            }
        });
        var subscription = subscribe(Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key("fresh", TYPE, "node-1"), 1,
                                                                                 ConsistencyMode.FRESH, handler, TestSeams.NONE));
        awaitTrue(subscription::isIdle);
        var writer = openAppend("A", 1);
        var first  = commitAppend("B", 10);
        awaitTrue(() -> delivered.size() >= 10);
        assertThat(delivered).as("FRESH hands on above the held value").noneMatch(e -> e.globalOrder() == writer.globalOrder());
        writer.commit();
        awaitCaughtUp("fresh");
        assertThat(queryLong("SELECT count(*) FROM el_test_seen")).isEqualTo(11);
        assertThat(queryLong("SELECT count(DISTINCT g) FROM el_test_seen")).isEqualTo(11);
        assertThat(perAggregateOrderViolations(delivered)).isEmpty();
        assertThat(first).isGreaterThan(writer.globalOrder());
    }

    @Test
    void contiguous_periodic_holds_back_above_a_held_value_and_applies_every_event_once_in_global_order() throws Exception {
        var recorder     = new Recorder();
        var subscription = subscribe(Subscription.SubscriptionSpec.periodic(new ProgressStore.Key("contiguous", TYPE, "node-1"), 1,
                                                                            ConsistencyMode.CONTIGUOUS, recorder, TestSeams.NONE));
        awaitTrue(subscription::isIdle);
        var writer = openAppend("A", 1);
        var first  = commitAppend("B", 10);
        awaitOffered(first + 9);
        awaitReads(2);
        assertThat(recorder.delivered).as("nothing above the held value").isEmpty();
        writer.commit();
        awaitCaughtUp("contiguous");
        var expected = LongStream.rangeClosed(writer.globalOrder(), first + 9).boxed().toList();
        assertThat(recorder.delivered.stream().map(StoredEvent::globalOrder).toList()).isEqualTo(expected);
    }
}
